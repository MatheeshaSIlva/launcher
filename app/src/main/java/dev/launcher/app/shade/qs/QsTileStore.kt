package dev.launcher.app.shade.qs

import android.content.Context
import dev.launcher.app.AppLog
import dev.launcher.app.shade.Control
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tiles a person keeps in Android's shade (its edit page's current tiles): which, in what order, each small or large,
 * kept across starts (Android keeps them in its `sysui_qs_tiles` setting; ours in [PREFS]). Until the page changes them,
 * a Pixel's own ([QsTiles.DEFAULT]); Reset gives those back.
 */
object QsTileStore {
    private const val PREFS = "qs_tiles"
    private const val KEY = "tiles"

    @Volatile private var cached: List<QsTile>? = null

    /** The tiles now (read once, then kept). */
    fun tiles(ctx: Context): List<QsTile> = cached ?: load(ctx).also { cached = it }

    /** The tiles are [tiles] from now on. */
    fun set(ctx: Context, tiles: List<QsTile>) {
        cached = tiles
        val a = JSONArray()
        for (t in tiles) a.put(JSONObject().put("c", t.control.name).put("w", t.wide).apply { t.tile?.let { put("t", it) } })
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
    }

    /** A Pixel's own tiles again (Android's Reset). */
    fun reset(ctx: Context) {
        cached = QsTiles.DEFAULT
        prefs(ctx).edit().remove(KEY).apply()
    }

    /** Whether [tiles] are a Pixel's own (Reset has nothing to do). */
    fun isDefault(tiles: List<QsTile>): Boolean =
        tiles.size == QsTiles.DEFAULT.size && tiles.indices.all { tiles[it].key == QsTiles.DEFAULT[it].key && tiles[it].wide == QsTiles.DEFAULT[it].wide }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): List<QsTile> {
        val json = prefs(ctx).getString(KEY, null) ?: return QsTiles.DEFAULT
        return try {
            val a = JSONArray(json)
            val out = ArrayList<QsTile>(a.length())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val c = Control.entries.firstOrNull { it.name == o.optString("c") } ?: continue
                val wide = o.optBoolean("w", false)
                out += if (c == Control.APP_TILE) QsTiles.appTile(o.optString("t").takeIf { it.isNotEmpty() } ?: continue, wide)
                else QsTiles.tileOf(c, wide)
            }
            out.distinctBy { it.key }
        } catch (t: Throwable) {
            AppLog.log("[qs] the kept tiles could not be read (${t.message}): a Pixel's own")
            QsTiles.DEFAULT
        }
    }
}
