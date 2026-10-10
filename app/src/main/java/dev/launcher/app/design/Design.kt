package dev.launcher.app.design

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.launcher.app.AppLog
import dev.launcher.app.theme.Appearance
import java.io.File

/**
 * The live design system: the active theme with the user's edits over it, resolved for drawing. A theme is a file
 * (`assets/themes/<id>.json` built in, or `files/themes/<id>.json`, pushed over adb or made by the builder, which wins
 * over a built-in one of the same id); a theme can be built on another (`"extends": "ios27"`) and hold only what it
 * changes. The user's edits (the token editor) are kept per theme (`files/design/user.json` for iOS 27,
 * `user-<id>.json` for the others). Colours are blended at the current appearance ([Appearance.dark], so a light/dark
 * change crossfades every token on the same frames); numbers come in their own unit ([pt] turns points into pixels by
 * the scaling policy).
 *
 * Changing a token or the theme bumps [version] and calls the listeners (on the main thread): views that drew with tokens
 * draw again. Resolved values are cached until the next change; reading a token in a draw call is a map lookup.
 */
object Design {
    /** The theme every other one is checked against (it has every token the code reads) and the one used by default. */
    const val DEFAULT_THEME = "ios27"
    private const val ACTIVE = "design/theme.txt"
    /** How deep themes may be built on each other. */
    private const val MAX_EXTENDS = 6

    private lateinit var appCtx: Context
    /** The active theme and the ones it is built on, the base first. */
    private var chain: List<Theme> = emptyList()
    private var activeId = DEFAULT_THEME
    private val user = LinkedHashMap<String, Entry>()
    /** The resolver and its cache, replaced as a whole on a change (surfaces read tokens on their own threads). */
    private class State(val resolver: Resolver) { val cache = java.util.concurrent.ConcurrentHashMap<String, Value>() }
    @Volatile private var state = State(Resolver(emptyList()))
    private val resolver get() = state.resolver
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Bumped on every change of a token (views keyed on it draw again). */
    @Volatile var version = 0
        private set

    val themeName get() = chain.lastOrNull()?.name ?: "none"
    val themeId get() = activeId

    /** Loads the active theme and the user's edits (at app start, before anything draws). */
    fun init(ctx: Context) {
        if (chain.isNotEmpty()) return
        appCtx = ctx.applicationContext
        activeId = try { File(appCtx.filesDir, ACTIVE).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } } catch (_: Throwable) { null } ?: DEFAULT_THEME
        if (!load(activeId)) { activeId = DEFAULT_THEME; load(DEFAULT_THEME) }
        AppLog.log("[design] theme '$themeName' (${chain.joinToString(" < ") { it.name }}) ${resolver.keys().size} tokens, ${user.size} edited")
        // Over adb, for fast iteration: the theme files and the edits are read again (a theme pushed to files/themes/ shows at
        // once), or another theme is chosen; everything drawn with tokens redraws.
        //   adb shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app [--es theme ID]
        // Senders must hold DUMP (adb's shell does; other apps cannot).
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                val id = i.getStringExtra("theme")
                if (id != null) setTheme(id) else reload()
            }
        }
        val f = android.content.IntentFilter("dev.launcher.app.DESIGN_RELOAD")
        if (android.os.Build.VERSION.SDK_INT >= 33) appCtx.registerReceiver(r, f, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        else appCtx.registerReceiver(r, f, android.Manifest.permission.DUMP, null)
    }

    /** A theme the app can use: its id (file name), its name, and whether it comes with the app. */
    data class ThemeInfo(val id: String, val name: String, val builtIn: Boolean)

    /** The themes there are: the app's own, and the ones in `files/themes/` (which win over an app one of the same id). */
    fun themes(): List<ThemeInfo> {
        val out = LinkedHashMap<String, ThemeInfo>()
        try {
            for (n in appCtx.assets.list("themes") ?: emptyArray()) if (n.endsWith(".json")) {
                val id = n.removeSuffix(".json")
                readTheme(id)?.let { out[id] = ThemeInfo(id, it.name, true) }
            }
        } catch (_: Throwable) { }
        File(appCtx.filesDir, "themes").listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            val id = f.name.removeSuffix(".json")
            readTheme(id)?.let { out[id] = ThemeInfo(id, it.name, false) }
        }
        return out.values.toList()
    }

    /** Makes [id] the active theme (remembered); false if it cannot be used (the reason is logged), the current one stays. */
    fun setTheme(id: String): Boolean {
        val before = activeId
        if (!load(id)) return false
        activeId = id
        try { File(appCtx.filesDir, ACTIVE).apply { parentFile?.mkdirs() }.writeText(id) } catch (_: Throwable) { }
        changedOutside("theme '$themeName'" + if (before != id) " (was $before)" else " (reloaded)")
        return true
    }

    /** The active theme's files and the edits read again (written over adb): applied at once, as an edit in the editor is. */
    fun reload() {
        if (!load(activeId)) return
        changedOutside("reloaded")
    }

    private fun changedOutside(what: String) {
        version++
        AppLog.log("[design] $what: ${user.size} edited")
        val run = Runnable { for (l in listeners.toList()) l() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    /**
     * Loads theme [id] (with the ones it is built on) and its edits, if the result has every token the code reads, of the
     * same kind as [DEFAULT_THEME]'s (a missing or mistyped token would fail where it is drawn); else nothing changes.
     */
    private fun load(id: String): Boolean {
        val c = try { chainOf(id) } catch (t: Throwable) { AppLog.log("[design] theme '$id' cannot be used: ${t.message}"); return false }
        val edits = try {
            val f = File(appCtx.filesDir, userFile(id))
            if (f.exists()) Theme.parse(f.readText()).entries else emptyMap()
        } catch (t: Throwable) { AppLog.log("[design] edits of '$id' unreadable (${t.message}): ignored"); emptyMap() }
        val layers = c.map { it.entries } + listOf(LinkedHashMap(edits))
        val problem = if (id == DEFAULT_THEME && c.size == 1) checkResolves(layers) else checkAgainstDefault(layers)
        if (problem != null) { AppLog.log("[design] theme '$id' cannot be used: $problem"); return false }
        chain = c
        user.clear()
        user.putAll(edits)
        rebuild()
        return true
    }

    /** [id] and the themes it is built on, the base first. */
    private fun chainOf(id: String): List<Theme> {
        val out = ArrayList<Theme>()
        val seen = HashSet<String>()
        var cur: String? = id
        while (cur != null) {
            if (!seen.add(cur)) throw IllegalStateException("themes built on each other in a loop ($cur)")
            if (out.size >= MAX_EXTENDS) throw IllegalStateException("built on too many themes")
            val t = readTheme(cur) ?: throw IllegalStateException("theme '$cur' not found")
            out.add(0, t)
            cur = t.extends
        }
        return out
    }

    /** The theme file [id], from `files/themes/` or the app's own; null if there is none or it cannot be read. */
    private fun readTheme(id: String): Theme? = try {
        val f = File(appCtx.filesDir, "themes/$id.json")
        val text = if (f.exists()) f.readText() else appCtx.assets.open("themes/$id.json").bufferedReader().use { it.readText() }
        Theme.parse(text)
    } catch (t: Throwable) {
        if (t !is java.io.FileNotFoundException) AppLog.log("[design] theme file '$id' unreadable: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun checkResolves(layers: List<Map<String, Entry>>): String? {
        val r = Resolver(layers)
        for (k in r.keys()) try { r.resolve(k) } catch (t: Throwable) { return t.message }
        return null
    }

    private fun checkAgainstDefault(layers: List<Map<String, Entry>>): String? {
        checkResolves(layers)?.let { return it }
        val base = readTheme(DEFAULT_THEME) ?: return "the default theme is missing"
        return ThemeCheck.against(base.entries, layers)
    }

    private fun userFile(id: String) = if (id == DEFAULT_THEME) "design/user.json" else "design/user-$id.json"

    private fun rebuild() {
        state = State(Resolver(chain.map { it.entries } + listOf(LinkedHashMap(user))))
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    // ------------------------------------------------------------------ reading

    private fun value(key: String): Value = state.let { st -> st.cache.getOrPut(key) { st.resolver.resolve(key) } }

    /** [k]'s colour at the current appearance (ARGB). */
    fun color(k: ColorKey): Int {
        val v = value(k.name) as? Value.Color ?: throw IllegalStateException("token $k is not a colour")
        return Appearance.mix(v.light, v.dark)
    }

    /** A material's colour (a literal or a token) at the current appearance. */
    fun color(c: ColorValue): Int = resolver.colorPair(c).let { (l, d) -> Appearance.mix(l, d) }

    /** A blend token ([Blend]'s name; NORMAL if it is not one). */
    fun blend(k: ChoiceKey): Blend = try { Blend.valueOf(choice(k)) } catch (_: IllegalArgumentException) { Blend.NORMAL }

    /** [k]'s number in its own unit (points for lengths). */
    fun num(k: NumberKey): Float = (value(k.name) as? Value.Number ?: throw IllegalStateException("token $k is not a number")).v

    /** [k] (a length in points) in pixels, at [unitPx] pixels per point ([Scale.unitPx]). */
    fun pt(k: NumberKey, unitPx: Float): Float = num(k) * unitPx

    fun spring(k: SpringKey): Spring = (value(k.name) as? Value.SpringV ?: throw IllegalStateException("token $k is not a spring")).spring
    fun choice(k: ChoiceKey): String = (value(k.name) as? Value.Choice ?: throw IllegalStateException("token $k is not a choice")).option
    fun text(k: TextKey): TextStyle = (value(k.name) as? Value.Text ?: throw IllegalStateException("token $k is not a text style")).style
    fun material(k: MaterialKey): Material = (value(k.name) as? Value.Mat ?: throw IllegalStateException("token $k is not a material")).material

    // ------------------------------------------------------------------ the token editor's side

    /** Every token key of the theme (and of the user's edits), sorted. */
    fun keys(): List<String> = resolver.keys().sorted()

    /** [key] as the theme defines it (null: not edited) and as the user edited it. */
    fun themeEntry(key: String): Entry? {
        for (t in chain.asReversed()) t.entries[key]?.let { return it }
        return null
    }
    fun userEntry(key: String): Entry? = user[key]
    fun entry(key: String): Entry? = resolver.entry(key)
    fun resolved(key: String): Value = value(key)

    /** The user changed [key] to [v] (saved at once, every surface draws again). */
    fun set(key: String, v: Value) {
        user[key] = Entry(v, Provenance.User)
        changed()
    }

    /** Back to the theme's value. */
    fun reset(key: String) {
        if (user.remove(key) != null) changed()
    }

    fun resetAll() {
        if (user.isEmpty()) return
        user.clear()
        changed()
    }

    private fun changed() {
        rebuild()
        version++
        save()
        val run = Runnable { for (l in listeners.toList()) l() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private fun save() {
        try {
            val f = File(appCtx.filesDir, userFile(activeId))
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Theme.write("user", user))
            tmp.renameTo(f)
        } catch (t: Throwable) {
            AppLog.log("[design] saving the edits failed: ${t.message}")
        }
    }
}

/**
 * How points become pixels: the theme's policy (`sys.scale.policy`). "reference-width": the screen's short side is
 * `sys.scale.reference-width` points wide (iOS 27's iPhone: 402 pt; on the S24 an element comes out the size it is on an
 * iPhone 17 Pro); "density": one point is one Android dp (follows the system's display size).
 */
/** [this] style on [p]: font, weight, size and letter spacing at [unitPx] pixels per point (line height is the caller's). */
fun TextStyle.applyTo(p: android.graphics.Paint, unitPx: Float) {
    p.typeface = if (family == "display") dev.launcher.app.theme.Fonts.display(weight) else dev.launcher.app.theme.Fonts.text(weight)
    p.textSize = sizePt * unitPx
    p.letterSpacing = if (sizePt > 0f) trackingPt / sizePt else 0f
}

/** [this] blend as the canvas draws it (null: the canvas has no such mode; the caller draws it normally). */
fun Blend.toBlendMode(): android.graphics.BlendMode? = when (this) {
    Blend.NORMAL -> android.graphics.BlendMode.SRC_OVER
    Blend.MULTIPLY -> android.graphics.BlendMode.MULTIPLY
    Blend.SCREEN -> android.graphics.BlendMode.SCREEN
    Blend.OVERLAY -> android.graphics.BlendMode.OVERLAY
    Blend.DARKEN -> android.graphics.BlendMode.DARKEN
    Blend.LIGHTEN -> android.graphics.BlendMode.LIGHTEN
    Blend.COLOR_DODGE -> android.graphics.BlendMode.COLOR_DODGE
    Blend.COLOR_BURN -> android.graphics.BlendMode.COLOR_BURN
    Blend.LINEAR_DODGE -> android.graphics.BlendMode.PLUS
    Blend.LINEAR_BURN -> null
    Blend.HARD_LIGHT -> android.graphics.BlendMode.HARD_LIGHT
    Blend.SOFT_LIGHT -> android.graphics.BlendMode.SOFT_LIGHT
    Blend.LUMINOSITY -> android.graphics.BlendMode.LUMINOSITY
    Blend.COLOR -> android.graphics.BlendMode.COLOR
    Blend.HUE -> android.graphics.BlendMode.HUE
    Blend.SATURATION -> android.graphics.BlendMode.SATURATION
}

object Scale {
    val POLICY = ChoiceKey("sys.scale.policy")
    val REFERENCE_WIDTH = NumberKey("sys.scale.reference-width")

    fun unitPx(ctx: Context, shortSidePx: Int): Float = when (Design.choice(POLICY)) {
        "density" -> ctx.resources.displayMetrics.density
        else -> shortSidePx / Design.num(REFERENCE_WIDTH)
    }

}
