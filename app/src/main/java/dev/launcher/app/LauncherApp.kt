package dev.launcher.app

import android.app.Application

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must stay the first log line: tests are only trusted when this matches the commit under test.
        AppLog.log("BUILD ${buildStamp()}")
        Watchdog.startHeartbeat()
        dev.launcher.app.theme.Fonts.init(this)
        // The theme before the appearance: the appearance's palette is the theme's.
        dev.launcher.app.design.Design.init(this)
        dev.launcher.app.theme.Appearance.init(this)
        // Dark mode switched anywhere (also while an app is in front): home crossfades at once, so it is already in the new
        // appearance (and its picture behind closing cards too) when it is next seen.
        registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                // A theme built on the system's colours first (they may have changed with the wallpaper), then the appearance.
                dev.launcher.app.design.Design.onConfiguration()
                dev.launcher.app.theme.Appearance.onConfiguration(newConfig)
            }
            @Deprecated("Deprecated in Java") override fun onLowMemory() {}
        })
        dev.launcher.app.apps.Icons.init(this)
        dev.launcher.app.apps.LaunchStats.init(this)
        dev.launcher.app.apps.Apps.init(this)
        GestureNav.init(this)
        Badges.testHook(this)
        ShizukuLink.init(this)
        SafetyNotification.show(this)
    }

    fun buildStamp(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }
}
