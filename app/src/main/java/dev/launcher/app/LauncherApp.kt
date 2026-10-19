package dev.launcher.app

import android.app.Application

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must stay the first log line: tests are only trusted when this matches the commit under test.
        AppLog.log("BUILD ${buildStamp()}")
        logPaletteHandling()
        Watchdog.startHeartbeat()
        dev.launcher.app.theme.Fonts.init(this)
        // Other apps in the launcher's colours (Look › Colours › Other apps): before the design system, which judges the
        // phone's own palette through what it gave Android; it follows every design change.
        dev.launcher.app.design.SystemPalette.init(this)
        // The theme before the appearance: the appearance's palette is the theme's.
        dev.launcher.app.design.Design.init(this)
        resources.configuration.let { dev.launcher.app.design.Design.onScale(it.densityDpi, it.fontScale) }
        // The user's arrangement (which layout each element uses, where it lives): before home or gesture nav read it.
        dev.launcher.app.layout.Setup.init(this)
        dev.launcher.app.theme.Appearance.init(this)
        // Dark mode switched anywhere (also while an app is in front): home crossfades at once, so it is already in the new
        // appearance (and its picture behind closing cards too) when it is next seen.
        registerComponentCallbacks(object : android.content.ComponentCallbacks {
            private var config = android.content.res.Configuration(resources.configuration)

            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                val diff = config.diff(newConfig)
                config = android.content.res.Configuration(newConfig)
                // The scale (Display size, Font size), the palette (it comes with the assets: an overlay, Material You's or
                // One UI's, read again only then: not at every turn of the phone), the appearance.
                dev.launcher.app.design.Design.onScale(newConfig.densityDpi, newConfig.fontScale)
                if (diff and ASSETS_PATHS != 0) dev.launcher.app.design.Design.onConfiguration()
                dev.launcher.app.theme.Appearance.onConfiguration(newConfig)
            }
            @Deprecated("Deprecated in Java") override fun onLowMemory() {}
        })
        dev.launcher.app.apps.Icons.init(this)
        dev.launcher.app.apps.LaunchStats.init(this)
        dev.launcher.app.apps.Apps.init(this)
        GestureNav.init(this)
        Badges.testHook(this)
        Wallpaper.testHook(this)
        ShizukuLink.init(this)
        SafetyNotification.show(this)
    }

    /**
     * Whether Android keeps home through a change of the palette (an overlay: res/values/config_changes.xml names it as a
     * number), as the phone read our manifest: made anew at each change, home's reveals were cut and it arrived as after a
     * cold start.
     */
    private fun logPaletteHandling() = try {
        val c = packageManager.getActivityInfo(android.content.ComponentName(this, HomeActivity::class.java), 0).configChanges
        AppLog.log("[app] home " + (if (c and ASSETS_PATHS != 0) "stays through palette changes" else "is made anew at each palette change") +
            String.format(" (configChanges 0x%08x)", c))
    } catch (_: Throwable) { }

    fun buildStamp(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }

    private companion object {
        /** ActivityInfo.CONFIG_ASSETS_PATHS (a runtime overlay changed: the phone's palette). */
        const val ASSETS_PATHS = 0x80000000.toInt()
    }
}
