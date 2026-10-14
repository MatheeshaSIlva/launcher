package dev.launcher.app.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The settings' own choices that are not the design's (Developer's switches), kept in the app's preferences. */
internal object SettingsPrefs {
    private const val PREFS = "settings"
    private const val TEST_PRESETS = "test_presets"
    private const val DEVELOPER = "developer"

    /** The developer tools show on Phone (turned on by seven taps on the build, off on their page). */
    var developer by mutableStateOf(false)
        private set
    /** Animation presets made for testing (Eased) show in Motion with the others (while the developer tools are on). */
    var testPresets by mutableStateOf(false)
        private set
    private var loaded = false

    /** Read once, before the settings first draw (the pages are built with what it says). */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        developer = p.getBoolean(DEVELOPER, false)
        testPresets = p.getBoolean(TEST_PRESETS, false)
    }

    fun setDeveloper(ctx: Context, on: Boolean) {
        developer = on
        prefs(ctx).edit().putBoolean(DEVELOPER, on).apply()
    }

    fun setTestPresets(ctx: Context, on: Boolean) {
        testPresets = on
        prefs(ctx).edit().putBoolean(TEST_PRESETS, on).apply()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
