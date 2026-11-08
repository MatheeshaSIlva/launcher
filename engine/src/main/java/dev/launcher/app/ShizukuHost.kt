package dev.launcher.app

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/** Shizuku's user service as the shell host (ShizukuLink's code before T-001 slice 2, moved unchanged). */
class ShizukuHost : ShellHost {
    private companion object { const val PERMISSION_REQUEST = 1001 }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: android.app.Application
    private lateinit var sink: ShellHost.Sink
    private var generation = 0
    private var binding = false
    private var bound = false
    /** The service Shizuku started for us: it outlives Shizuku's server, holding the status bar's flags (below). */
    private var live: IShellService? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, ShellService::class.java.name))
            .processNameSuffix("shell")
            .daemon(false)
            .tag("launcher-shell")
            // A new build must replace a still-running service from the old build.
            .version(EngineHost.current("buildStamp").buildStamp().hashCode())
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            binding = false
            bound = service != null
            live = service
            AppLog.log("[shizuku] service connected: ${service != null}")
            if (service != null) sink.connected(generation, service) else sink.changed()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            bound = false
            live = null
            AppLog.log("[shizuku] service disconnected")
            sink.lost(generation)
            // If only our service died (Shizuku still up), bring it back. If Shizuku died, binder-received rebinds later.
            main.postDelayed({ if (!bound && hasPermission()) bind() }, 500)
        }
    }

    override fun init(app: android.app.Application, sink: ShellHost.Sink, generation: Int) {
        this.app = app
        this.sink = sink
        this.generation = generation
        Shizuku.addBinderReceivedListenerSticky {
            AppLog.log("[shizuku] binder received")
            if (hasPermission()) bind()
            sink.changed()
        }
        Shizuku.addBinderDeadListener {
            binding = false
            bound = false
            AppLog.log("[shizuku] binder dead (Shizuku stopped)")
            // Our service does not die with Shizuku's server, and it holds the flags that keep Android's shade and gesture
            // bar away: left running, the phone had neither ours (the link is gone) nor Android's (the owner, S24, T-001).
            // Its binder still answers: stop it, so the system drops the flags at once.
            live?.let { s ->
                live = null
                Thread({ try { s.destroy() } catch (_: Throwable) { } }, "shell-stop").start()
                AppLog.log("[shizuku] our shell service stopped with it")
            }
            sink.lost(generation)
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            val granted = result == PackageManager.PERMISSION_GRANTED
            AppLog.log("[shizuku] permission ${if (granted) "granted" else "denied"}")
            if (granted) bind()
            sink.changed()
        }
    }

    override fun state(): ShizukuLink.State = when {
        !running() -> ShizukuLink.State.NOT_RUNNING
        !hasPermission() -> ShizukuLink.State.NO_PERMISSION
        else -> ShizukuLink.State.CONNECTING
    }

    override fun connect() {
        when {
            !running() -> AppLog.log("[shizuku] not running. Start Shizuku (wireless debugging), then tap Connect again.")
            !hasPermission() -> {
                AppLog.log("[shizuku] requesting permission; approve the dialog")
                Shizuku.requestPermission(PERMISSION_REQUEST)
            }
            !bound -> bind()
        }
    }

    override fun bindQuietly() { if (hasPermission()) bind() }

    private fun bind() {
        if (bound || binding) return
        binding = true
        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            binding = false
            AppLog.log("[shizuku] bind failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        sink.changed()
    }

    private fun running() = Shizuku.pingBinder() && !Shizuku.isPreV11()
    private fun hasPermission() = running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
}
