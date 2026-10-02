package dev.launcher.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * App side of the watchdog. The shell loop (see ShellService) runs our restore plan when the heartbeat stops, so a
 * crashed or killed app never leaves the system with a hidden status bar or animations off.
 * Rule: change system state only AFTER [sync] has handed the watchdog a plan that undoes it.
 */
object Watchdog {
    /** Blocking binder call; never on the main thread. Hands the current restore plan to the loop and starts it if needed. */
    fun sync(ctx: Context, s: IShellService) {
        try {
            val reply = s.watchdogArm(SystemRestore.restorePlan(ctx))
            if ("FIRED" in reply) {
                // The loop already restored the animation scales while we were gone.
                SystemRestore.clearAnimationRecords(ctx)
                s.watchdogArm("")
                AppLog.log("[watchdog] it fired since the last arm (${reply.substringAfter("FIRED").substringBefore(";").trim()}); system was restored")
            }
            if ("VANISHED" in reply) AppLog.log("[watchdog] WARNING: the previous loop was killed without running its restore plan")
            AppLog.log("[watchdog] armed: ${reply.substringBefore(";")}")
        } catch (t: Throwable) {
            AppLog.log("[watchdog] arm failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private var heartbeat: Thread? = null

    /** Process-wide: the heartbeat lives exactly as long as this process. Idempotent. */
    @Synchronized fun startHeartbeat() {
        if (heartbeat != null) return
        heartbeat = Thread {
            while (true) {
                try { ShizukuLink.service?.heartbeat() } catch (_: Throwable) { /* service gone: heartbeat stops, watchdog fires */ }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "heartbeat"; isDaemon = true; start() }
    }

    fun start(ctx: Context) {
        try {
            ctx.startForegroundService(Intent(ctx, WatchdogService::class.java))
        } catch (t: Throwable) {
            // Background start restrictions: the next foreground moment (home screen) starts it.
            AppLog.log("[watchdog] heartbeat service not started: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

/**
 * Foreground service: keeps the process alive and out of the cached-app freezer, so the heartbeat keeps going while
 * other apps are in front. Its notification is the safety notification.
 */
class WatchdogService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = SafetyNotification.build(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(SafetyNotification.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(SafetyNotification.ID, n)
        Watchdog.startHeartbeat()
        return START_STICKY
    }
}
