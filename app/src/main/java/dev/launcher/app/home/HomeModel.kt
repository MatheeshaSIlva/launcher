package dev.launcher.app.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import dev.launcher.app.AppLog
import dev.launcher.app.apps.AppEntry
import dev.launcher.app.apps.Apps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Something placed on a home page. Pages are flowed in order, like iOS: no gaps, widgets take a block of cells. */
sealed class HomeItem {
    abstract fun toJson(): JSONObject

    data class App(val key: String) : HomeItem() {
        override fun toJson() = JSONObject().put("t", "app").put("k", key)
    }

    /** Folders arrive with edit mode; defined now so saved layouts never need migrating. */
    data class Folder(val id: String, val name: String, val apps: List<String>) : HomeItem() {
        override fun toJson() = JSONObject().put("t", "folder").put("id", id).put("n", name).put("apps", JSONArray(apps))
    }

    /** A widget: ours ([kind] "clock") or an Android widget ([APP], with its bound [id] and [provider] component). */
    data class Widget(val kind: String, val spanX: Int, val spanY: Int, val id: Int = 0, val provider: String? = null) : HomeItem() {
        override fun toJson() = JSONObject().put("t", "widget").put("w", kind).put("sx", spanX).put("sy", spanY).apply {
            if (id != 0) put("id", id)
            provider?.let { put("p", it) }
        }

        companion object {
            const val APP = "app"
        }
    }

    companion object {
        fun fromJson(o: JSONObject): HomeItem? = when (o.optString("t")) {
            "app" -> App(o.getString("k"))
            "folder" -> Folder(o.getString("id"), o.optString("n"), o.getJSONArray("apps").let { a -> List(a.length()) { a.getString(it) } })
            "widget" -> Widget(o.getString("w"), o.optInt("sx", 1), o.optInt("sy", 1), o.optInt("id", 0), o.optString("p").ifEmpty { null })
            else -> null
        }
    }
}

/** A placed item: its cell and span on the page grid. */
data class Placed(val item: HomeItem, val col: Int, val row: Int, val spanX: Int, val spanY: Int)

class HomeLayout(
    val pages: MutableList<MutableList<HomeItem>>,
    val dock: MutableList<String>,
    /** Every app key that existed at the last sync: anything installed later is "new". */
    val seen: MutableSet<String>,
)

/**
 * Loads, saves and keeps the home layout in step with installed apps. Saved as JSON in the app's files; writes happen on
 * a worker, atomically (temp file + rename).
 */
object HomeModel {
    private val io = Executors.newSingleThreadExecutor()
    private const val FILE = "home_layout.json"

    /** Flows [items] into a [cols] x [rows] grid in order. Items that do not fit are left out (callers keep pages within capacity). */
    fun place(items: List<HomeItem>, cols: Int, rows: Int): List<Placed> {
        val used = Array(rows) { BooleanArray(cols) }
        val out = ArrayList<Placed>()
        for (item in items) {
            val (sx, sy) = if (item is HomeItem.Widget) item.spanX.coerceIn(1, cols) to item.spanY.coerceIn(1, rows) else 1 to 1
            search@ for (r in 0..rows - sy) for (c in 0..cols - sx) {
                if ((r until r + sy).all { rr -> (c until c + sx).all { cc -> !used[rr][cc] } }) {
                    for (rr in r until r + sy) for (cc in c until c + sx) used[rr][cc] = true
                    out += Placed(item, c, r, sx, sy)
                    break@search
                }
            }
        }
        return out
    }

    fun capacityLeft(items: List<HomeItem>, cols: Int, rows: Int): Int =
        cols * rows - place(items, cols, rows).sumOf { it.spanX * it.spanY }

    fun load(ctx: Context): HomeLayout? = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val pages = o.getJSONArray("pages").let { ps ->
                MutableList(ps.length()) { i ->
                    val a = ps.getJSONArray(i)
                    (0 until a.length()).mapNotNull { HomeItem.fromJson(a.getJSONObject(it)) }.toMutableList()
                }
            }
            val dock = o.getJSONArray("dock").let { a -> MutableList(a.length()) { a.getString(it) } }
            val seen = o.optJSONArray("seen")?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } ?: mutableSetOf()
            HomeLayout(pages, dock, seen)
        }
    } catch (t: Throwable) {
        AppLog.log("[home] layout unreadable (${t.javaClass.simpleName}: ${t.message}); starting fresh")
        null
    }

    fun save(ctx: Context, l: HomeLayout) {
        val json = JSONObject()
            .put("version", 1)
            .put("pages", JSONArray(l.pages.map { p -> JSONArray(p.map { it.toJson() }) }))
            .put("dock", JSONArray(l.dock))
            .put("seen", JSONArray(l.seen.toList()))
            .toString()
        val dir = ctx.filesDir
        io.execute {
            try {
                val tmp = File(dir, "$FILE.tmp")
                tmp.writeText(json)
                tmp.renameTo(File(dir, FILE))
            } catch (t: Throwable) {
                AppLog.log("[home] saving the layout failed: ${t.message}")
            }
        }
    }

    fun reset(ctx: Context) { File(ctx.filesDir, FILE).delete() }

    /**
     * A first layout, iOS-like: the dock gets phone, messages, browser and camera; page one a clock and the everyday system
     * apps; the apps the user installed follow on the next pages. Everything else is in the App Library.
     */
    fun seed(ctx: Context, cfg: HomeConfig): HomeLayout {
        val apps = Apps.all
        val used = HashSet<String>()
        fun keyOfPkg(pkg: String?): String? = pkg?.let { Apps.forPkg(it) }?.key?.takeIf { it !in used }?.also { used += it }
        fun defaultFor(intent: Intent): String? = try {
            ctx.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName?.takeIf { it != "android" }
        } catch (_: Throwable) { null }

        val dock = listOfNotNull(
            keyOfPkg(defaultFor(Intent(Intent.ACTION_DIAL))),
            keyOfPkg(try { Telephony.Sms.getDefaultSmsPackage(ctx) } catch (_: Throwable) { null }),
            keyOfPkg(defaultFor(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))),
            keyOfPkg(defaultFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))),
        ).take(cfg.dockSlots).toMutableList()

        val first = mutableListOf<HomeItem>(HomeItem.Widget("clock", cfg.columns, 2))
        val preferred = listOf(
            listOf("com.android.settings"),
            listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
            listOf("com.samsung.android.calendar", "com.google.android.calendar"),
            listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"),
            listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"),
            listOf("com.android.vending"),
            listOf("com.google.android.apps.maps"),
            listOf("com.google.android.youtube"),
            listOf("com.google.android.gm"),
            listOf("com.samsung.android.app.notes", "com.google.android.keep"),
            listOf("com.sec.android.app.myfiles", "com.google.android.apps.nbu.files"),
            listOf("com.sec.android.daemonapp", "com.google.android.apps.weather"),
            listOf("com.samsung.android.app.contacts", "com.google.android.contacts"),
        )
        // The dev panel (updater, log) on page one, until onboarding and settings exist.
        apps.firstOrNull { it.internal }?.let { first += HomeItem.App(it.key); used += it.key }
        for (choices in preferred) {
            if (capacityLeft(first, cfg.columns, cfg.rows) == 0) break
            choices.firstNotNullOfOrNull { keyOfPkg(it) }?.let { first += HomeItem.App(it) }
        }
        val pages = mutableListOf(first)
        val userApps = apps.filter { !it.system && !it.internal && it.key !in used }
        var page = mutableListOf<HomeItem>()
        for (e in userApps) {
            if (page.size == cfg.columns * cfg.rows) { pages += page; page = mutableListOf() }
            page += HomeItem.App(e.key); used += e.key
        }
        if (page.isNotEmpty()) pages += page
        AppLog.log("[home] new layout: dock ${dock.size}, ${pages.size} pages")
        return HomeLayout(pages, dock, apps.map { it.key }.toMutableSet())
    }

    /**
     * Removes apps that are gone and (if [HomeConfig.newAppsOnHome]) adds newly installed ones after the last item, on a new
     * page when the last is full. Returns true if the layout changed.
     */
    fun sync(l: HomeLayout, apps: List<AppEntry>, cfg: HomeConfig): Boolean {
        val installed = apps.associateBy { it.key }
        var changed = false
        if (l.dock.removeAll { it !in installed }) changed = true
        for (p in l.pages) {
            val before = p.size
            p.replaceAll { item -> if (item is HomeItem.Folder) item.copy(apps = item.apps.filter { it in installed }) else item }
            p.removeAll { (it is HomeItem.App && it.key !in installed) || (it is HomeItem.Folder && it.apps.isEmpty()) }
            if (p.size != before) changed = true
        }
        if (l.pages.size > 1 && l.pages.removeAll { it.isEmpty() }) changed = true
        if (l.pages.isEmpty()) { l.pages += mutableListOf<HomeItem>(); changed = true }
        val fresh = apps.filter { it.key !in l.seen && !it.internal }
        if (fresh.isNotEmpty()) {
            if (cfg.newAppsOnHome) {
                for (e in fresh) {
                    var last = l.pages.last()
                    if (capacityLeft(last, cfg.columns, cfg.rows) == 0) { last = mutableListOf(); l.pages += last }
                    last += HomeItem.App(e.key)
                }
            }
            changed = true
        }
        if (l.seen.retainAll(installed.keys)) changed = true
        if (l.seen.addAll(installed.keys)) changed = true
        return changed
    }
}
