package dev.launcher.app.theme

import android.content.Context
import android.graphics.Typeface
import dev.launcher.app.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Every family on fonts.google.com, for the settings' font search: its name, category, weights and how popular it is.
 * Read from Google Fonts' public list (`fonts.google.com/metadata/fonts`, 2.7 MB of JSON, read as a stream) and kept on the
 * phone as a small table (`files/fonts/google/families.tsv`, ~60 KB), read again from Google when two weeks old. Without a
 * connection the kept table serves; never fetched and no connection: no list (the search says so).
 */
object GoogleFontCatalog {
    class Family(val name: String, val category: String, val weights: List<Int>, val rank: Int) {
        internal val lower = name.lowercase()
        internal val joined = lower.replace(" ", "")
    }

    /** Google's categories, as the list names them, and as the settings show them. */
    val CATEGORIES = listOf("Sans Serif" to "Sans serif", "Serif" to "Serif", "Display" to "Display",
        "Handwriting" to "Handwriting", "Monospace" to "Mono")

    private const val SOURCE = "https://fonts.google.com/metadata/fonts"
    private const val MAX_AGE_MS = 14L * 24 * 3600 * 1000

    @Volatile private var cached: List<Family>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "fonts/google/families.tsv")

    /** The families if they are in memory already (no waiting), else null. */
    fun cachedNow(): List<Family>? = cached

    /**
     * The kept table read into memory if it is recent (blocking: a background thread), so the search opens with its list
     * (it showed "Loading" for a moment); never asks Google (that waits for the search to open).
     */
    fun warm(ctx: Context) {
        if (cached != null) return
        val f = file(ctx)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() >= MAX_AGE_MS) return
        try { read(f).takeIf { it.isNotEmpty() }?.let { cached = it } } catch (_: Throwable) { }
    }

    /**
     * The families, most popular first (blocking: call it on a background thread). The kept table if it is recent, else
     * Google's list read again (the kept one if that fails); null if there is none to be had.
     */
    fun families(ctx: Context): List<Family>? {
        cached?.let { return it }
        val f = file(ctx)
        val kept = if (f.exists()) try { read(f) } catch (_: Throwable) { null } else null
        if (kept != null && kept.isNotEmpty() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return kept.also { cached = it }
        val t0 = android.os.SystemClock.uptimeMillis()
        val fresh = try { fetch() } catch (t: Throwable) { AppLog.log("[fonts] Google Fonts' list not read: ${t.javaClass.simpleName}: ${t.message}"); null }
        if (fresh != null && fresh.isNotEmpty()) {
            AppLog.log("[fonts] Google Fonts' list: ${fresh.size} families (${android.os.SystemClock.uptimeMillis() - t0} ms), first ${fresh.take(5).joinToString { it.name }}")
            try { write(f, fresh) } catch (t: Throwable) { AppLog.log("[fonts] Google Fonts' list not kept: ${t.message}") }
            cached = fresh
            return fresh
        }
        return kept?.takeIf { it.isNotEmpty() }?.also { cached = it }
    }

    /**
     * The best matches for [query] in [category] (Google's name; null: all), at most [limit]: a name that is the query, then
     * names starting with it (spaces ignored: "dmsans" finds DM Sans), then a word starting with it, then names containing
     * it; within each, the more popular first. No query: the most popular.
     */
    fun search(all: List<Family>, query: String, category: String?, limit: Int): List<Family> {
        val q = query.trim().lowercase()
        val pool = if (category == null) all else all.filter { it.category == category }
        if (q.isEmpty()) return pool.take(limit)
        val qj = q.replace(" ", "")
        val scored = ArrayList<Pair<Int, Family>>()
        for (f in pool) {
            val s = when {
                f.lower == q -> 0
                f.lower.startsWith(q) || f.joined.startsWith(qj) -> 1
                f.lower.split(' ').any { it.startsWith(q) } -> 2
                f.lower.contains(q) || f.joined.contains(qj) -> 3
                else -> continue
            }
            scored += s to f
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.rank })).take(limit).map { it.second }
    }

    /** Google's list, read as a stream (only the fields used here are kept), most popular first. */
    private fun fetch(): List<Family> {
        val c = URL(SOURCE).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val out = ArrayList<Family>(2000)
            android.util.JsonReader(c.inputStream.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "familyMetadataList") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) readFamily(r)?.let { out += it }
                    r.endArray()
                }
                r.endObject()
            }
            return out.sortedBy { it.rank }
        } finally { c.disconnect() }
    }

    private fun readFamily(r: android.util.JsonReader): Family? {
        var name: String? = null
        var category = ""
        val weights = ArrayList<Int>()
        var rank = Int.MAX_VALUE
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "family" -> name = r.nextString()
                "category" -> category = if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); "" } else r.nextString()
                "popularity" -> rank = if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); Int.MAX_VALUE } else r.nextInt()
                // Its styles: "400", "400i", "700"... (the upright ones are the weights).
                "fonts" -> {
                    r.beginObject()
                    while (r.hasNext()) { r.nextName().toIntOrNull()?.let { weights += it }; r.skipValue() }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return name?.let { Family(it, category, weights.sorted(), rank) }
    }

    private fun write(f: File, families: List<Family>) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for (x in families) w.append(x.name).append('\t').append(x.category).append('\t')
                .append(x.weights.joinToString(",")).append('\t').append(x.rank.toString()).append('\n')
        }
        if (!tmp.renameTo(f)) tmp.delete()
    }

    private fun read(f: File): List<Family> = f.readLines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 4) null
        else Family(p[0], p[1], p[2].split(',').mapNotNull { it.toIntOrNull() }, p[3].toIntOrNull() ?: Int.MAX_VALUE)
    }
}

/**
 * Google fonts shown in their own face while they are browsed (the settings' font search, its chips): the regular weight
 * only, downloaded through Play services' font provider into the cache (the system may clear it), three at a time. Unlike
 * a font a theme uses ([Fonts]), one arriving changes nothing else: no surface is built again.
 */
object FontPreviews {
    private val faces = ConcurrentHashMap<String, Typeface>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Where previews are downloaded to run, three at a time (the rows a search shows ask together). */
    val dispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "font-previews") }.asCoroutineDispatcher()
    }

    /** [name]'s regular face if it is in memory already (no waiting: a row shows it from its first frame). */
    fun ready(name: String): Typeface? = faces[name]

    /**
     * [name]'s regular face (blocking: on [dispatcher]): the one a theme's use downloaded already, else the cached preview,
     * else downloaded now; null if it cannot be had (asked again a minute after a failure).
     */
    fun load(ctx: Context, name: String): Typeface? {
        faces[name]?.let { return it }
        // One family at a time: two rows asking for it together downloaded it twice into one file (not a font after).
        return synchronized(locks.computeIfAbsent(name) { Any() }) { loadOnce(ctx, name) }
    }

    private val locks = ConcurrentHashMap<String, Any>()

    /** [name]'s regular face if it is on the phone (a theme's use, or a preview kept in the cache), else null; never downloads. */
    fun kept(ctx: Context, name: String): Typeface? {
        faces[name]?.let { return it }
        val kept = GoogleFonts.file(ctx, name, 400)
        if (kept.exists()) GoogleFonts.load(kept, 400)?.let { faces[name] = it; return it }
        val cache = cacheFile(ctx, name)
        if (cache.exists()) GoogleFonts.load(cache, 400)?.let { faces[name] = it; return it }
        return null
    }

    private fun cacheFile(ctx: Context, name: String) = File(ctx.cacheDir, "font-previews/" + name.replace(' ', '_') + "-400.ttf")

    private fun loadOnce(ctx: Context, name: String): Typeface? {
        faces[name]?.let { return it }
        val kept = GoogleFonts.file(ctx, name, 400)
        if (kept.exists()) GoogleFonts.load(kept, 400)?.let { faces[name] = it; return it }
        val cache = cacheFile(ctx, name)
        if (cache.exists()) GoogleFonts.load(cache, 400)?.let { faces[name] = it; return it }
        failedAt[name]?.let { if (android.os.SystemClock.uptimeMillis() - it < 60_000) return null }
        val why = try { GoogleFonts.download(ctx, name, 400, cache) } catch (t: Throwable) { t.toString() }
        if (why != null) { failedAt[name] = android.os.SystemClock.uptimeMillis(); AppLog.log("[fonts] preview of '$name': $why"); return null }
        return GoogleFonts.load(cache, 400)?.also { faces[name] = it }
    }
}
