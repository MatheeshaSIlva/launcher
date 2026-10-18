package dev.launcher.app.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The settings' own choices that are not the design's (Developer's switches, the folds left open), kept in the app's preferences. */
internal object SettingsPrefs {
    private const val PREFS = "settings"
    private const val TEST_PRESETS = "test_presets"
    private const val DEVELOPER = "developer"
    private const val OPEN_FOLDS = "open_folds"

    /** The developer tools show on System (turned on by seven taps on the build, off on their page). */
    var developer by mutableStateOf(false)
        private set
    /** Animation presets made for testing (Eased) show in Motion with the others (while the developer tools are on). */
    var testPresets by mutableStateOf(false)
        private set
    /**
     * The folds left open (docs/SETTINGS_NAVIGATION.md, [Fold]): each stays as it was left, in the next session too (a fold
     * opened once is not asked for again every time).
     */
    var openFolds by mutableStateOf(emptySet<String>())
        private set
    private var loaded = false
    private var app: Context? = null

    /** Read once, before the settings first draw (the pages are built with what it says). */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        app = ctx.applicationContext
        val p = prefs(ctx)
        developer = p.getBoolean(DEVELOPER, false)
        testPresets = p.getBoolean(TEST_PRESETS, false)
        openFolds = p.getStringSet(OPEN_FOLDS, null)?.toSet() ?: emptySet()
    }

    fun setDeveloper(ctx: Context, on: Boolean) {
        developer = on
        prefs(ctx).edit().putBoolean(DEVELOPER, on).apply()
    }

    fun setTestPresets(ctx: Context, on: Boolean) {
        testPresets = on
        prefs(ctx).edit().putBoolean(TEST_PRESETS, on).apply()
    }

    /** Fold [id] open or folded, kept for next time. */
    fun setFold(id: String, open: Boolean) {
        if ((id in openFolds) == open) return
        openFolds = if (open) openFolds + id else openFolds - id
        app?.let { prefs(it).edit().putStringSet(OPEN_FOLDS, openFolds).apply() }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
