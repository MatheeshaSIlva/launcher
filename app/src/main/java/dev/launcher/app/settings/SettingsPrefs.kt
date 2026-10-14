package dev.launcher.app.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The settings' own choices that are not the design's (Developer's switches), kept in the app's preferences. */
internal object SettingsPrefs {
    private const val PREFS = "settings"
    private const val TEST_PRESETS = "test_presets"

    /** Animation presets made for testing (Eased) show in Motion with the others. */
    var testPresets by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        testPresets = prefs(ctx).getBoolean(TEST_PRESETS, false)
    }

    fun setTestPresets(ctx: Context, on: Boolean) {
        testPresets = on
        prefs(ctx).edit().putBoolean(TEST_PRESETS, on).apply()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
