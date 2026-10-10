package dev.launcher.app.layout

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.launcher.app.AppLog
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** One element's setup: the chosen layout, where it lives, and the options set (only valid values; a missing one is its default). */
data class ElementSetup(val layout: String, val placement: String, val options: Map<String, Any> = emptyMap())

/**
 * The user's arrangement (docs/PLAN_LAYOUTS_THEMES.md): for each [Element] its layout, its placement and its options,
 * kept in `files/setup.json`. Separate from the theme. Everything read from it is valid: an unknown layout becomes the
 * element's default, a placement the layout does not offer its default placement, an option out of range or of the
 * wrong type its default.
 *
 * Over adb, for tests (senders must hold DUMP, as adb's shell does):
 *   adb shell am broadcast -a dev.launcher.app.SETUP -p dev.launcher.app --es element drawer [--es layout ID]
 *     [--es placement ID] [--es option KEY=VALUE]
 */
object Setup {
    private const val FILE = "setup.json"
    private const val VERSION = 1
    private const val LEGACY_PREFS = "home_config"

    @Volatile private var state: Map<Element, ElementSetup> = normalise(emptyMap())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }   // (lazy: the unit tests have no Android)
    private var appCtx: Context? = null

    /** Reads the setup (or makes it from the home settings of earlier builds), at app start. */
    fun init(ctx: Context) {
        if (appCtx != null) return
        val c = ctx.applicationContext
        appCtx = c
        val f = File(c.filesDir, FILE)
        state = try {
            if (f.exists()) parse(f.readText())
            else fromLegacy(c.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)).also { write(it) }
        } catch (t: Throwable) {
            AppLog.log("[setup] unreadable (${t.javaClass.simpleName}: ${t.message}): defaults")
            normalise(emptyMap())
        }
        AppLog.log("[setup] ${describe(state)}")
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(rc: Context, i: android.content.Intent) {
                val e = Element.of(i.getStringExtra("element")) ?: run { AppLog.log("[setup] no such element: ${i.getStringExtra("element")}"); return }
                val opt = i.getStringExtra("option")?.split('=', limit = 2)?.takeIf { it.size == 2 }
                set(e, i.getStringExtra("layout"), i.getStringExtra("placement"), if (opt != null) mapOf(opt[0] to opt[1]) else emptyMap())
            }
        }
        val filter = android.content.IntentFilter("dev.launcher.app.SETUP")
        if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        else c.registerReceiver(r, filter, android.Manifest.permission.DUMP, null)
    }

    fun of(e: Element): ElementSetup = state.getValue(e)
    fun layout(e: Element): LayoutSpec = Layouts.find(e, of(e).layout) ?: Layouts.default(e)
    fun placement(e: Element): Placement = layout(e).let { it.placement(of(e).placement) ?: it.defaultPlacement }

    /** The value of option [key] of [e]'s layout (its default when not set); null if the layout has no such option. */
    fun option(e: Element, key: String): Any? {
        val spec = layout(e).option(key) ?: return null
        return of(e).options[key] ?: spec.default
    }

    fun bool(e: Element, key: String, fallback: Boolean): Boolean = option(e, key) as? Boolean ?: fallback

    /**
     * Changes [e]'s layout, placement and/or options (null: unchanged; an option value of null: back to its default).
     * Invalid values are refused (logged). Saved at once; listeners run on the main thread. True if anything changed.
     */
    fun set(e: Element, layout: String? = null, placement: String? = null, options: Map<String, Any?> = emptyMap()): Boolean {
        val changed: Boolean
        synchronized(this) {
            val cur = of(e)
            val spec = if (layout != null) Layouts.find(e, layout) ?: run { AppLog.log("[setup] ${e.id}: no layout '$layout'"); return false }
                else Layouts.find(e, cur.layout) ?: Layouts.default(e)
            val p = when {
                placement != null -> spec.placement(placement)?.id ?: run { AppLog.log("[setup] ${e.id}: ${spec.id} has no placement '$placement'"); return false }
                spec.placement(cur.placement) != null -> cur.placement
                else -> spec.defaultPlacement.id
            }
            // Options carry over to a new layout only where it has them (and they are valid there).
            val opts = LinkedHashMap<String, Any>()
            for ((k, v) in cur.options) spec.option(k)?.accept(v)?.let { opts[k] = it }
            for ((k, v) in options) {
                val o = spec.option(k) ?: run { AppLog.log("[setup] ${e.id}: ${spec.id} has no option '$k'"); return false }
                if (v == null) { opts.remove(k); continue }
                opts[k] = o.accept(v) ?: run { AppLog.log("[setup] ${e.id}: '$v' is not a valid ${o.title}"); return false }
            }
            val next = ElementSetup(spec.id, p, opts)
            changed = next != cur
            if (changed) {
                state = state + (e to next)
                write(state)
                AppLog.log("[setup] ${e.id}: ${describe(e, next)}")
            }
        }
        if (changed) main.post { for (l in listeners) l() }
        return changed
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun write(s: Map<Element, ElementSetup>) {
        val c = appCtx ?: return
        try {
            val f = File(c.filesDir, FILE)
            val tmp = File(c.filesDir, "$FILE.tmp")
            tmp.writeText(toJson(s))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (t: Throwable) {
            AppLog.log("[setup] not saved: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ pure parts (unit-tested)

    /** [s] completed and made valid: every element present, unknown layouts, placements and options replaced or dropped. */
    internal fun normalise(s: Map<Element, ElementSetup>): Map<Element, ElementSetup> = Element.entries.associateWith { e ->
        val given = s[e]
        val spec = Layouts.find(e, given?.layout) ?: Layouts.default(e)
        val p = spec.placement(given?.placement)?.id ?: spec.defaultPlacement.id
        val opts = LinkedHashMap<String, Any>()
        given?.options?.forEach { (k, v) -> spec.option(k)?.accept(v)?.let { opts[k] = it } }
        ElementSetup(spec.id, p, opts)
    }

    internal fun parse(json: String): Map<Element, ElementSetup> {
        val root = JSONObject(json)
        val elements = root.optJSONObject("elements") ?: JSONObject()
        val out = HashMap<Element, ElementSetup>()
        for (e in Element.entries) {
            val o = elements.optJSONObject(e.id) ?: continue
            val opts = HashMap<String, Any>()
            o.optJSONObject("options")?.let { oo -> for (k in oo.keys()) oo.opt(k)?.let { opts[k] = it } }
            out[e] = ElementSetup(o.optString("layout", ""), o.optString("placement", ""), opts)
        }
        return normalise(out)
    }

    internal fun toJson(s: Map<Element, ElementSetup>): String {
        val elements = JSONObject()
        for (e in Element.entries) {
            val v = s[e] ?: continue
            val opts = JSONObject()
            for ((k, x) in v.options) opts.put(k, x)
            elements.put(e.id, JSONObject().put("layout", v.layout).put("placement", v.placement).put("options", opts))
        }
        return JSONObject().put("version", VERSION).put("elements", elements).toString(2)
    }

    /** The setup earlier builds kept in the home settings: the drawer's style and placement, the home options. */
    internal fun fromLegacy(drawerPlacement: String?, drawerStyle: String?, labels: Boolean?, widgetLabels: Boolean?, newApps: Boolean?): Map<Element, ElementSetup> {
        val placement = when (drawerPlacement) {
            "PAGE_BEFORE_FIRST" -> "page-before-first"
            "SWIPE_UP" -> "swipe-up"
            else -> "page-after-last"
        }
        val drawer = Layouts.APP_LIBRARY.id   // the only style earlier builds had ([drawerStyle] APP_LIBRARY)
        val home = LinkedHashMap<String, Any>()
        labels?.let { home["show-labels"] = it }
        widgetLabels?.let { home["show-widget-labels"] = it }
        newApps?.let { home["new-apps-on-home"] = it }
        return normalise(mapOf(
            Element.DRAWER to ElementSetup(drawer, placement),
            Element.HOME to ElementSetup(Layouts.IOS_HOME.id, Layouts.IOS_HOME.defaultPlacement.id, home),
        ))
    }

    private fun fromLegacy(p: android.content.SharedPreferences): Map<Element, ElementSetup> = fromLegacy(
        p.getString("drawer_placement", null), p.getString("drawer_style", null),
        if (p.contains("show_labels")) p.getBoolean("show_labels", true) else null,
        if (p.contains("show_widget_labels")) p.getBoolean("show_widget_labels", true) else null,
        if (p.contains("new_apps_on_home")) p.getBoolean("new_apps_on_home", true) else null,
    )

    internal fun describe(e: Element, v: ElementSetup): String =
        "${v.layout} (${v.placement})" + if (v.options.isEmpty()) "" else " " + v.options.entries.joinToString(", ", "{", "}") { "${it.key}=${it.value}" }

    private fun describe(s: Map<Element, ElementSetup>): String = Element.entries.joinToString("; ") { e -> "${e.id} " + describe(e, s.getValue(e)) }
}
