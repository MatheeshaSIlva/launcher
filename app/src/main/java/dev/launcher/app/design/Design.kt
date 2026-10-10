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
    /**
     * Where the theme's colours come from: "theme" (its own) or "wallpaper" (Material You: the theme's `materialYou`
     * section over it, its accents as the system palette's roles). A user's choice, kept with their edits of the theme.
     */
    val COLOR_SOURCE = ChoiceKey("sys.color.source")

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
        if (!load(activeId)) {
            // The remembered theme is gone (a removed file, an older build's): the default from now on, remembered too.
            activeId = DEFAULT_THEME
            load(DEFAULT_THEME)
            try { File(appCtx.filesDir, ACTIVE).apply { parentFile?.mkdirs() }.writeText(DEFAULT_THEME) } catch (_: Throwable) { }
        }
        paletteAt = paletteStamp()
        AppLog.log("[design] theme '$themeName' (${chain.joinToString(" < ") { it.name }}) ${resolver.keys().size} tokens, ${user.size} edited")
        // Over adb, for fast iteration: the theme files and the edits are read again (a theme pushed to files/themes/ shows at
        // once), or another theme is chosen; everything drawn with tokens redraws.
        //   adb shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app [--es theme ID]
        // Senders must hold DUMP (adb's shell does; other apps cannot).
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                // --ez palette true: logs the system's colours (Material roles and tonal palettes), for theme work.
                if (i.getBooleanExtra("palette", false)) { logPalette(); return }
                // --es colors wallpaper|theme: the theme's colours from the wallpaper (Material You) or its own.
                i.getStringExtra("colors")?.let { setColorSource(it); return }
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

    /** A Google font the theme names was downloaded (theme/Fonts): every surface draws again with it. */
    fun fontArrived(name: String) = changedOutside("font '$name' arrived")

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
        // Checked with and without its Material You section (either may be shown).
        for (my in listOf(false, true)) {
            val layers = layersOf(c, edits, my)
            val problem = if (id == DEFAULT_THEME && c.size == 1) checkResolves(layers) else checkAgainstDefault(layers)
            if (problem != null) { AppLog.log("[design] theme '$id' cannot be used" + (if (my) " in wallpaper colours" else "") + ": $problem"); return false }
        }
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

    private fun layersOf(c: List<Theme>, edits: Map<String, Entry>, materialYou: Boolean) = ThemeLayers.of(c, edits, materialYou)

    private fun rebuild() {
        state = State(Resolver(layersOf(chain, user, ThemeLayers.fromWallpaper(chain, user))) { name -> paletteColor(name) })
    }

    /** The theme's colours come from the wallpaper (Material You) instead of its own. */
    val wallpaperColors: Boolean get() = (try { value(COLOR_SOURCE.name) } catch (_: Throwable) { null } as? Value.Choice)?.option == "wallpaper"

    /** Turns the wallpaper's colours on ("wallpaper") or off ("theme") for the active theme (kept with its edits). */
    fun setColorSource(source: String) {
        if (source != "wallpaper" && source != "theme") { AppLog.log("[design] colours from '$source'? (wallpaper or theme)"); return }
        set(COLOR_SOURCE.name, Value.Choice(source))
        AppLog.log("[design] colours from the " + if (source == "wallpaper") "wallpaper (Material You)" else "theme")
    }

    // ------------------------------------------------------------------ the system's palette (Material You)

    private val colorIds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val palette = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private const val NO_COLOR = 0x00FFFFFE   // cached "no such colour" (never a real palette colour: transparent)
    private var paletteAt = 0L

    /** The palette colour [name] (an `android.R.color` field, `system_primary_dark`), cached until the palette changes. */
    private fun paletteColor(name: String): Int? = palette.getOrPut(name) { systemColor(name) ?: NO_COLOR }.takeIf { it != NO_COLOR }

    private fun systemColor(name: String): Int? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        val id = colorIds.getOrPut(name) { try { android.R.color::class.java.getField(name).getInt(null) } catch (_: Throwable) { 0 } }
        if (id == 0) return null
        return try { appCtx.resources.getColor(id, null) } catch (_: Throwable) { null }
    }

    /** A fingerprint of the palette's key colours (equal: nothing to look up again). */
    private fun paletteStamp(): Long {
        var h = 17L
        for (n in listOf("system_accent1_500", "system_accent2_500", "system_accent3_500", "system_neutral1_500", "system_neutral2_500"))
            h = h * 31 + (systemColor(n) ?: 0)
        return h
    }

    /**
     * The configuration changed (the app's ComponentCallbacks): the palette may have changed with the wallpaper. Palette
     * colours are looked up again and every surface draws again, on the same frame (a light/dark change crossfades on its own).
     */
    fun onConfiguration() {
        if (chain.isEmpty()) return
        val stamp = paletteStamp()
        if (stamp == paletteAt) return
        paletteAt = stamp
        palette.clear()
        rebuild()
        changedOutside("the wallpaper's colours changed")
    }

    private fun logPalette() {
        val names = android.R.color::class.java.fields.map { it.name }.filter { it.startsWith("system_") }.sorted()
        val out = names.mapNotNull { n -> systemColor(n)?.let { "$n=#" + String.format("%08x", it).substring(2) } }
        out.chunked(12).forEach { AppLog.log("[design] palette " + it.joinToString(" ")) }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    // ------------------------------------------------------------------ reading

    private fun value(key: String): Value = state.let { st -> st.cache.getOrPut(key) { st.resolver.resolve(key) } }

    /** [k]'s colour at the current appearance (ARGB); palette colours looked up (cached). */
    fun color(k: ColorKey): Int {
        val v = value(k.name) as? Value.Color ?: throw IllegalStateException("token $k is not a colour")
        if (!v.dynamic) return Appearance.mix(v.light, v.dark)
        return resolver.pair(v).let { (l, d) -> Appearance.mix(l, d) }
    }

    /** A colour value's light and dark ARGB, its palette references looked up (the token editor shows them). */
    fun pair(v: Value.Color): Pair<Int, Int> = resolver.pair(v)

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
    p.typeface = dev.launcher.app.theme.Fonts.of(family, weight, sizePt)
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
