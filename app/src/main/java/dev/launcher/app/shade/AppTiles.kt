package dev.launcher.app.shade

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.service.quicksettings.TileService
import dev.launcher.app.AppLog
import dev.launcher.app.ShizukuLink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Apps' Quick Settings tiles ([TileService]s) as Control Center's controls from apps (iOS 18+'s third-party controls):
 * found through the package manager, shown with their own name and symbol, and run by SystemUI, since only it may bind a
 * tile. A tap is SystemUI's click on it (`cmd statusbar click-tile`, through the shell); its state is what SystemUI shows
 * for it (`dumpsys ... SystemUIService QSTileHost`: label, subtitle, on / off / unavailable).
 *
 * SystemUI runs a tile only while it is among its own Quick Settings tiles: a tile put on Control Center is added there
 * (`add-tile`; the stock panel stays blocked, so it never shows), and taken out again when it leaves Control Center if it
 * was not there before (remembered in [PREFS]). Without Shizuku the tiles are listed but cannot be used.
 *
 * Tiles are named by their component, flattened ([ComponentName.flattenToString]): Control Center's layout keeps them so.
 */
object AppTiles {
    class Info(
        val id: String,
        val pkg: String,
        /** The tile's own name (its service's label). */
        val label: String,
        /** Its app's name: the gallery's section. */
        val appLabel: String,
        /** Its symbol (drawn tinted, as the stock panel does), or null if it has none fit for tinting. */
        val icon: Drawable.ConstantState?,
        /** It says it is a switch (on / off), not a button. */
        val toggleable: Boolean,
    )

    /** What SystemUI shows for a tile now: its label and subtitle (the app may change both), and [state] (Tile.STATE_*). */
    class State(val label: String?, val secondary: String?, val state: Int)

    @Volatile var all: List<Info> = emptyList()
        private set
    /** True once the tiles were found at least once (before that, a tile on the page is not known to be gone). */
    @Volatile var found = false
        private set
    @Volatile private var states: Map<String, State> = emptyMap()

    private val io = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Pair<Handler, () -> Unit>>()
    private const val PREFS = "app_tiles"
    private const val ADDED = "added_by_us"

    fun addListener(h: Handler, l: () -> Unit) { listeners += h to l }
    fun removeListener(l: () -> Unit) { listeners.removeAll { it.second === l } }
    private fun changed() { for ((h, l) in listeners) h.post(l) }

    fun info(id: String): Info? = all.firstOrNull { it.id == id }
    fun state(id: String): State? = states[id]

    /** Tiles seen on since the app started: switches, even those that do not say so. */
    private val seenOn = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun wasOn(id: String): Boolean = id in seenOn

    // ------------------------------------------------------------------ finding them

    @Volatile private var watching = false

    /** Finds the tiles now (off the caller's thread), and again whenever an app is installed, updated or removed. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        refresh(app)
        if (watching) return
        watching = true
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { refresh(app) }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
    }

    private fun refresh(ctx: Context) = io.execute {
        val t0 = android.os.SystemClock.uptimeMillis()
        val pm = ctx.packageManager
        val list = try {
            pm.queryIntentServices(Intent(TileService.ACTION_QS_TILE), PackageManager.GET_META_DATA)
        } catch (t: Throwable) { AppLog.log("[tiles] could not list: ${t.message}"); return@execute }
        val out = ArrayList<Info>()
        for (r in list) {
            val si = r.serviceInfo ?: continue
            if (si.packageName == ctx.packageName || !si.exported || si.permission != android.Manifest.permission.BIND_QUICK_SETTINGS_TILE) continue
            val cn = ComponentName(si.packageName, si.name)
            val enabled = try { pm.getComponentEnabledSetting(cn) } catch (_: Throwable) { PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }
            if (enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || (enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !si.enabled)) continue
            val label = try { si.loadLabel(pm).toString().trim() } catch (_: Throwable) { "" }
            val appLabel = try { si.applicationInfo.loadLabel(pm).toString().trim() } catch (_: Throwable) { si.packageName }
            out += Info(cn.flattenToString(), si.packageName, label.ifEmpty { appLabel }, appLabel, symbolOf(pm, si),
                si.metaData?.getBoolean(TileService.META_DATA_TOGGLEABLE_TILE, false) == true)
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER, Info::appLabel).thenBy(String.CASE_INSENSITIVE_ORDER, Info::label))
        all = out
        found = true
        AppLog.log("[tiles] ${out.size} tiles from ${out.distinctBy { it.pkg }.size} apps in ${android.os.SystemClock.uptimeMillis() - t0} ms" +
            out.count { it.icon == null }.let { if (it > 0) " ($it without a symbol to tint)" else "" })
        changed()
    }

    /**
     * The tile's own symbol (its service's icon), only if it is one to tint (a vector or a transparent bitmap, as tiles'
     * are): a full-colour app icon would draw as a white blob, so those fall back to a generic symbol.
     */
    private fun symbolOf(pm: PackageManager, si: android.content.pm.ServiceInfo): Drawable.ConstantState? {
        if (si.icon == 0) return null
        val d = try { pm.getDrawable(si.packageName, si.icon, si.applicationInfo) } catch (_: Throwable) { null } ?: return null
        if (d is AdaptiveIconDrawable) return null
        if (d is BitmapDrawable && d.bitmap?.hasAlpha() != true) return null
        // A symbol coloured from its app's theme (?attr/colorControlNormal) is drawn with nothing where the theme is missing.
        if (d.canApplyTheme()) try {
            val theme = pm.getResourcesForApplication(si.applicationInfo).newTheme().apply { applyStyle(android.R.style.Theme_DeviceDefault, true) }
            d.applyTheme(theme)
        } catch (_: Throwable) { }
        return d.constantState
    }

    // ------------------------------------------------------------------ their state, and using them (through the shell)

    /** Reads what SystemUI shows for every tile it runs (~0.2 s on the S24, off the caller's thread). */
    fun readStates() {
        val s = ShizukuLink.service ?: return
        io.execute { readStatesNow(s) }
    }

    private fun readStatesNow(s: dev.launcher.app.IShellService) {
        val out = try { s.runShell("dumpsys activity service com.android.systemui/.SystemUIService QSTileHost") } catch (_: Throwable) { return }
        val next = parse(out)
        for ((id, st) in next) if (st.state == android.service.quicksettings.Tile.STATE_ACTIVE) seenOn += id
        if (!same(next, states)) { states = next; changed() }
    }

    private fun same(a: Map<String, State>, b: Map<String, State>): Boolean =
        a.size == b.size && a.all { (k, v) -> b[k]?.let { it.label == v.label && it.secondary == v.secondary && it.state == v.state } == true }

    /** A component as SystemUI writes it ("pkg/.Cls" or "pkg/full.Cls") in its full form, as [ComponentName.flattenToString]. */
    internal fun flat(spec: String): String? {
        val slash = spec.indexOf('/')
        if (slash <= 0 || slash == spec.length - 1) return null
        val pkg = spec.substring(0, slash)
        val cls = spec.substring(slash + 1)
        return "$pkg/${if (cls.startsWith(".")) pkg + cls else cls}"
    }

    /** SystemUI's tiles' states from its dump (each tile's State.toString(); labels may hold line breaks). */
    internal fun parse(dump: String): Map<String, State> {
        val map = HashMap<String, State>()
        for (part in dump.split("spec=custom(").drop(1)) {
            val close = part.indexOf(')')
            if (close <= 0) continue
            val id = flat(part.substring(0, close)) ?: continue
            if (id in map) continue
            val body = part.substring(close)
            val label = Regex("label=(.*?),secondaryLabel=", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            val sec = Regex("secondaryLabel=(.*?),contentDescription=", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            val st = Regex(",state=(\\d)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            fun clean(v: String?) = v?.replace(Regex("\\s*\\n\\s*"), " ")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            map[id] = State(clean(label), clean(sec), st)
        }
        return map
    }

    /** SystemUI's own tiles (its Quick Settings list), as components. */
    private fun systemTiles(s: dev.launcher.app.IShellService): List<ComponentName> {
        val out = try { s.runShell("settings get secure sysui_qs_tiles") } catch (_: Throwable) { return emptyList() }
        return Regex("custom\\(([^)]+)\\)").findAll(out).mapNotNull { ComponentName.unflattenFromString(it.groupValues[1]) }.toList()
    }

    /** Puts [id] among SystemUI's tiles if it is not there (so it runs and reports its state); remembers it was us. */
    private fun ensureAddedNow(ctx: Context, s: dev.launcher.app.IShellService, id: String): Boolean {
        val cn = ComponentName.unflattenFromString(id) ?: return false
        if (cn in systemTiles(s)) return true
        val r = try { s.runShell("cmd statusbar add-tile ${cn.flattenToShortString()}") } catch (t: Throwable) { "ERROR ${t.message}" }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(ADDED, (prefs.getStringSet(ADDED, emptySet()) ?: emptySet()) + id).apply()
        AppLog.log("[tiles] added to SystemUI's tiles: $id${r.trim().let { if (it != "[exit 0]") " ($it)" else "" }}")
        // SystemUI binds it on its own time: its state follows in a moment.
        Thread.sleep(400)
        return true
    }

    /** [id] was put on Control Center: SystemUI starts running it (so its state is known before the first tap). */
    fun added(ctx: Context, id: String) {
        val s = ShizukuLink.service ?: return
        io.execute { if (ensureAddedNow(ctx, s, id)) readStatesNow(s) }
    }

    /** [id] left Control Center: out of SystemUI's tiles again if we put it there. */
    fun removed(ctx: Context, id: String) {
        val s = ShizukuLink.service ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ours = prefs.getStringSet(ADDED, emptySet()) ?: emptySet()
        if (id !in ours) return
        io.execute {
            val cn = ComponentName.unflattenFromString(id) ?: return@execute
            try { s.runShell("cmd statusbar remove-tile ${cn.flattenToShortString()}") } catch (_: Throwable) { return@execute }
            prefs.edit().putStringSet(ADDED, ours - id).apply()
            AppLog.log("[tiles] taken out of SystemUI's tiles: $id")
        }
    }

    /**
     * A tap on [id]: SystemUI clicks it (the app does what its tile does: a switch, or opening something). Its state is
     * read again after (twice: an app may take a moment). False if it cannot be used (no shell).
     */
    fun click(ctx: Context, id: String): Boolean {
        val s = ShizukuLink.service ?: return false
        io.execute {
            if (!ensureAddedNow(ctx, s, id)) return@execute
            val cn = ComponentName.unflattenFromString(id) ?: return@execute
            val r = try { s.runShell("cmd statusbar click-tile ${cn.flattenToShortString()}") } catch (t: Throwable) { "ERROR ${t.message}" }
            if (r.contains("ERROR") || r.contains("Exception")) AppLog.log("[tiles] click failed: $id: $r")
            Thread.sleep(250)
            readStatesNow(s)
            Thread.sleep(600)
            readStatesNow(s)
        }
        return true
    }

    /** A long press: the tile's settings (the app's own screen for it), else the app, else its info screen. */
    fun settingsIntent(ctx: Context, id: String): Intent? {
        val cn = ComponentName.unflattenFromString(id) ?: return null
        val pm = ctx.packageManager
        val prefs = Intent(TileService.ACTION_QS_TILE_PREFERENCES).setPackage(cn.packageName).putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
        if (pm.resolveActivity(prefs, 0) != null) return prefs
        return pm.getLaunchIntentForPackage(cn.packageName)
            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", cn.packageName, null))
    }
}
