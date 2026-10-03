package dev.launcher.app.apps

import android.content.Context
import kotlin.math.exp
import kotlin.math.ln

/**
 * How often and how recently each app was opened from the launcher; feeds the App Library's Suggestions (and later
 * search ranking). Kept per app key in private preferences.
 */
object LaunchStats {
    private const val PREFS = "launch_stats"
    private lateinit var ctx: Context

    fun init(context: Context) { ctx = context.applicationContext }

    fun record(key: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (count, _) = parse(p.getString(key, null))
        p.edit().putString(key, "${count + 1},${System.currentTimeMillis()}").apply()
    }

    /** Up to [n] apps, best first: recent use counts most, frequent use breaks ties (decays over about two days). */
    fun suggestions(n: Int, exclude: Set<String> = emptySet()): List<AppEntry> {
        val now = System.currentTimeMillis()
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.all.mapNotNull { (key, v) ->
            if (key in exclude) return@mapNotNull null
            val e = Apps[key] ?: return@mapNotNull null
            val (count, last) = parse(v as? String)
            val ageHours = (now - last) / 3_600_000.0
            e to exp(-ageHours / 48.0) * (1.0 + ln(1.0 + count))
        }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    private fun parse(s: String?): Pair<Int, Long> {
        val parts = s?.split(',') ?: return 0 to 0L
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }
}
