package dev.launcher.app

import android.app.Application

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must stay the first log line: tests are only trusted when this matches the commit under test.
        AppLog.log("BUILD ${buildStamp()}")
        Watchdog.startHeartbeat()
        ShizukuLink.init(this)
        SafetyNotification.show(this)
    }

    fun buildStamp(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }
}
