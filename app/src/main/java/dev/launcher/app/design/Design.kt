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
 * Motion is a layer of its own (docs/PLAN_LAYOUTS_THEMES.md, B3): the active animation preset (`assets/motion/<id>.json`
 * or `files/motion/<id>.json`, the same file format, built on each other the same way) holds every `motion.*` token,
 * a theme none; the user picks a preset apart from the theme, and their edits of it are kept per preset
 * (`files/design/motion-edits-<id>.json`). Both resolve together: code reads a motion role as any other token.
 *
 * Changing a token, the theme or the preset bumps [version] and calls the listeners (on the main thread): views that drew
 * with tokens draw again. Resolved values are cached until the next change; reading a token in a draw call is a map lookup.
 */
object Design {
    /** The theme every other one is checked against (it has every token the code reads) and the one used by default. */
    const val DEFAULT_THEME = "ios27"
    private const val ACTIVE = "design/theme.txt"
    /** The animation preset every other one is checked against and the one used by default. */
    const val DEFAULT_MOTION = "ios27"
    private const val ACTIVE_MOTION = "design/motion.txt"
    private const val THEMES = "themes"
    private const val PRESETS = "motion"
    /** How deep themes may be built on each other. */
    private const val MAX_EXTENDS = 6
    /**
     * Where the theme's colours come from: "theme" (its own) or "wallpaper" (Material You: the theme's `materialYou`
     * section over it, its accents as the system palette's roles). A user's choice, kept with their edits of the theme.
     */
    val COLOR_SOURCE = ChoiceKey("sys.color.source")
    /** Material You's style (MaterialYou.Style: the phone's, tonal, vibrant...) and intensity (0 grey .. 1 .. 2). */
    val COLOR_STYLE = ChoiceKey("sys.color.style")
    val COLOR_INTENSITY = NumberKey("sys.color.intensity")
    /** The colour Material You's palette is made from: "wallpaper" (its main colour) or one of the user's ("#rrggbb"). */
    val COLOR_SEED = ChoiceKey("sys.color.seed")
    /** Key colours the user set by hand, per palette (MaterialYou.KEY_PALETTES): "auto" or "#rrggbb". */
    val COLOR_KEYS = mapOf('P' to ChoiceKey("sys.color.key.primary"), 'S' to ChoiceKey("sys.color.key.secondary"),
        'T' to ChoiceKey("sys.color.key.tertiary"), 'N' to ChoiceKey("sys.color.key.neutral"), 'V' to ChoiceKey("sys.color.key.neutral-variant"))

    /** "#rrggbb" as an opaque colour, else null ("wallpaper", "auto"). */
    fun hexColor(s: String?): Int? = s?.takeIf { it.length == 7 && it[0] == '#' }?.substring(1)?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    fun hexOf(c: Int): String = "#%06x".format(c and 0xFFFFFF)

    private lateinit var appCtx: Context
    /** The active theme and the ones it is built on, the base first. */
    private var chain: List<Theme> = emptyList()
    private var activeId = DEFAULT_THEME
    private val user = LinkedHashMap<String, Entry>()
    /** The active animation preset and the ones it is built on (the base first), and the user's edits of it. */
    private var motionChain: List<Theme> = emptyList()
    private var motionId = DEFAULT_MOTION
    private val motionUser = LinkedHashMap<String, Entry>()
    /**
     * The resolver and its cache, replaced as a whole on a change (surfaces read tokens on their own threads). [bound]: a
     * material's colour references are resolved here too (an element's own theme: drawing code reads a material's colours
     * through the main theme).
     */
    private class State(val resolver: Resolver, val bound: Boolean = false) {
        val cache = java.util.concurrent.ConcurrentHashMap<String, Value>()
        /** Curves as code reads them: a motion role's at the preset's speed and bounce ([curve]). */
        val curves = java.util.concurrent.ConcurrentHashMap<String, Curve>()
        fun value(key: String): Value = cache.getOrPut(key) {
            val v = resolver.resolve(key)
            if (bound && v is Value.Mat) Value.Mat(v.material.bindRefs { k -> resolver.resolve(k) as? Value.Color ?: throw IllegalStateException("token '$k' is not a colour") }) else v
        }
    }
    @Volatile private var state = State(Resolver(emptyList()))
    private val resolver get() = state.resolver
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** A theme ready to resolve: the files it is built on (base first) and the user's edits of it. */
    private class Loaded(val id: String, val chain: List<Theme>, val edits: Map<String, Entry>)
    private const val ELEMENTS = "design/elements.json"
    /** Elements that follow a theme of their own (element -> theme id); the others follow the active theme. */
    private val elementIds = LinkedHashMap<dev.launcher.app.layout.Element, String>()
    private val elementThemes = HashMap<String, Loaded>()
    /** Their resolvers (an element's namespace resolves there: [TokenParts]); empty when everything follows one theme. */
    @Volatile private var parts: Map<dev.launcher.app.layout.Element, State> = emptyMap()

    /** Bumped on every change of a token (views keyed on it draw again). */
    @Volatile var version = 0
        private set

    /**
     * Bumped when what is built from the design may have changed (another theme, an edit of it, a font that arrived), not
     * by the palette or the animation preset: surfaces that build parts once (home, paints with a font) build them again
     * (B4, docs/PLAN_LAYOUTS_THEMES.md).
     */
    @Volatile var structure = 0
        private set

    val themeName get() = chain.lastOrNull()?.name ?: "none"
    val themeId get() = activeId
    val motionName get() = motionChain.lastOrNull()?.name ?: "none"
    val motionPresetId get() = motionId

    /** A token of the animation layer (a preset's), not of the theme. */
    fun isMotion(key: String) = key.startsWith("motion.")

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
        loadElements()
        moveMotionEdits()
        motionId = try { File(appCtx.filesDir, ACTIVE_MOTION).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } } catch (_: Throwable) { null } ?: DEFAULT_MOTION
        if (!loadMotion(motionId)) {
            motionId = DEFAULT_MOTION
            loadMotion(DEFAULT_MOTION)
            try { File(appCtx.filesDir, ACTIVE_MOTION).apply { parentFile?.mkdirs() }.writeText(DEFAULT_MOTION) } catch (_: Throwable) { }
        }
        paletteAt = paletteStamp()
        AppLog.log("[design] theme '$themeName' (${chain.joinToString(" < ") { it.name }}), motion '$motionName' (${motionChain.joinToString(" < ") { it.name }}): " +
            "${resolver.keys().size} tokens, ${user.size + motionUser.size} edited")
        // Over adb, for fast iteration: the theme files and the edits are read again (a theme pushed to files/themes/ shows at
        // once), or another theme is chosen; everything drawn with tokens redraws.
        //   adb shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app [--es theme ID] [--es motion ID]
        // Senders must hold DUMP (adb's shell does; other apps cannot).
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                // --ez palette true: logs the system's colours (Material roles and tonal palettes), for theme work.
                if (i.getBooleanExtra("palette", false)) { logPalette(); return }
                // --es files import|export|remove [--es path P]: the app's files in and out without run-as (DevFiles).
                i.getStringExtra("files")?.let { DevFiles.run(appCtx, it, i.getStringExtra("path")) { reload() }; return }
                // --es colors wallpaper|theme: the theme's colours from the wallpaper (Material You) or its own.
                i.getStringExtra("colors")?.let { setColorSource(it); return }
                val id = i.getStringExtra("theme")
                val preset = i.getStringExtra("motion")
                // --es element ID --es theme ID|follow: one element's own theme (follow: the active one again).
                i.getStringExtra("element")?.let { e ->
                    val el = dev.launcher.app.layout.Element.of(e) ?: run { AppLog.log("[design] no element '$e'"); return }
                    setElementTheme(el, id?.takeIf { it != "follow" })
                    return
                }
                if (id != null) setTheme(id)
                if (preset != null) setMotion(preset)
                if (id == null && preset == null) reload()
            }
        }
        val f = android.content.IntentFilter("dev.launcher.app.DESIGN_RELOAD")
        if (android.os.Build.VERSION.SDK_INT >= 33) appCtx.registerReceiver(r, f, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        else appCtx.registerReceiver(r, f, android.Manifest.permission.DUMP, null)
    }

    /**
     * A theme (or an animation preset) the app can use: its id (file name), its name, whether it comes with the app, a line
     * on what it is, and whether it is for testing (the settings show those only when asked to).
     */
    data class ThemeInfo(val id: String, val name: String, val builtIn: Boolean, val description: String = "", val test: Boolean = false)

    // The lists of themes and presets, read once (each read parses every file: 10-20 ms, and the settings asked for it in
    // a tile's composition, so a tap on Look recomposed for 20 ms on the S24); read again on [reload].
    @Volatile private var themeList: List<ThemeInfo>? = null
    @Volatile private var presetList: List<ThemeInfo>? = null

    /** The themes there are: the app's own, and the ones in `files/themes/` (which win over an app one of the same id). */
    fun themes(): List<ThemeInfo> = themeList ?: readThemes().also { themeList = it }

    private fun readThemes(): List<ThemeInfo> {
        val out = LinkedHashMap<String, ThemeInfo>()
        try {
            for (n in appCtx.assets.list(THEMES) ?: emptyArray()) if (n.endsWith(".json")) {
                val id = n.removeSuffix(".json")
                readFile(THEMES, id)?.let { out[id] = ThemeInfo(id, it.name, true, it.description, it.test) }
            }
        } catch (_: Throwable) { }
        File(appCtx.filesDir, THEMES).listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            val id = f.name.removeSuffix(".json")
            readFile(THEMES, id)?.let { out[id] = ThemeInfo(id, it.name, false, it.description, it.test) }
        }
        return out.values.toList()
    }

    /** The animation presets there are: the app's own, and the ones in `files/motion/`. */
    fun motionPresets(): List<ThemeInfo> = presetList ?: readPresets().also { presetList = it }

    private fun readPresets(): List<ThemeInfo> {
        val out = LinkedHashMap<String, ThemeInfo>()
        try {
            for (n in appCtx.assets.list(PRESETS) ?: emptyArray()) if (n.endsWith(".json")) {
                val id = n.removeSuffix(".json")
                readFile(PRESETS, id)?.let { out[id] = ThemeInfo(id, it.name, true, it.description, it.test) }
            }
        } catch (_: Throwable) { }
        File(appCtx.filesDir, PRESETS).listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            val id = f.name.removeSuffix(".json")
            readFile(PRESETS, id)?.let { out[id] = ThemeInfo(id, it.name, false, it.description, it.test) }
        }
        return out.values.toList()
    }

    /** Makes [id] the active animation preset (remembered); false if it cannot be used (logged), the current one stays. */
    fun setMotion(id: String): Boolean {
        val before = motionId
        if (!loadMotion(id)) return false
        motionId = id
        try { File(appCtx.filesDir, ACTIVE_MOTION).apply { parentFile?.mkdirs() }.writeText(id) } catch (_: Throwable) { }
        changedOutside("motion '$motionName'" + if (before != id) " (was $before)" else " (reloaded)", structural = false)
        return true
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
        themeList = null
        presetList = null
        presetChains.clear()
        val theme = load(activeId)
        val motion = loadMotion(motionId)
        // The elements' own themes too (a file pushed over adb shows at once there as well).
        for (id in elementThemes.keys.toList()) prepare(id)?.let { elementThemes[id] = it }
        rebuild()
        if (theme || motion) changedOutside("reloaded")
    }

    /** A Google font the theme names was downloaded (theme/Fonts): every surface draws again with it. */
    fun fontArrived(name: String) = changedOutside("font '$name' arrived")

    private fun changedOutside(what: String, structural: Boolean = true) {
        if (batching > 0) {
            batchStructural = (batchStructural ?: false) || structural
            AppLog.log("[design] $what (in a batch)")
            return
        }
        version++
        if (structural) structure++
        AppLog.log("[design] $what: ${user.size + motionUser.size} edited")
        val run = Runnable { for (l in listeners.toList()) l() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private var batching = 0
    /** What the changes in the running batch were (null: none yet; true: one was structural). */
    private var batchStructural: Boolean? = null

    /**
     * Runs [block]'s changes (a theme, colours, element themes, a preset, edits) as one: the listeners hear of them once at
     * the end, so whatever shows a change (home's reveal, the settings') shows it once (the settings' Apply).
     */
    fun batch(what: String, block: () -> Unit) {
        batching++
        try { block() } finally {
            batching--
            val s = batchStructural
            if (batching == 0 && s != null) {
                batchStructural = null
                changedOutside(what, s)
            }
        }
    }

    /**
     * Colour [k] as theme [id] gives it (light/dark blended now), whether or not it is in use: the settings show themes
     * before they are applied. Null if the theme cannot be used.
     */
    fun previewColor(id: String, k: ColorKey): Int? {
        val r = previews.getOrPut(id) {
            val l = (if (id == activeId) Loaded(id, chain, user) else elementThemes[id] ?: prepare(id)) ?: return null
            Resolver(layersOf(l.chain, l.edits, ThemeLayers.fromWallpaper(l.chain, l.edits))) { name -> paletteColor(name) }
        }
        val v = try { r.resolve(k.name) as? Value.Color } catch (_: Throwable) { null } ?: return null
        return r.pair(v).let { (l, d) -> Appearance.mix(l, d) }
    }

    private val previews = HashMap<String, Resolver>()

    /**
     * Curve [k] as animation preset [id] gives it, whether or not it is in use (the settings play a preset before it is
     * applied): with the user's edits of it ([edits]), at its own speed and bounce ([adjusted]: as code would read it).
     * Null if the preset cannot be read.
     */
    fun previewCurve(id: String, k: CurveKey, edits: Boolean = true, adjusted: Boolean = true): Curve? {
        val r = presetResolver(id, edits) ?: return null
        val c = (try { r.resolve(k.name) } catch (_: Throwable) { null } as? Value.CurveV)?.curve ?: return null
        if (!adjusted) return c
        fun num(n: NumberKey) = (try { r.resolve(n.name) } catch (_: Throwable) { null } as? Value.Number)?.v ?: 1f
        // The preset's speed and bounce, and the moment's on top of them.
        val m = dev.launcher.app.motion.Moments.of(k.name)
        return dev.launcher.app.motion.MotionAdjust.apply(c,
            dev.launcher.app.motion.MotionAdjust.speed(num(dev.launcher.app.motion.MotionTokens.ADJUST_SPEED), m?.let { num(it.speed) } ?: 1f),
            dev.launcher.app.motion.MotionAdjust.bounce(num(dev.launcher.app.motion.MotionTokens.ADJUST_BOUNCE), m?.let { num(it.bounce) } ?: 1f))
    }

    /** Token [key] as animation preset [id] gives it (with the user's edits of it, or [edits] false: the preset's own). */
    fun presetValue(id: String, key: String, edits: Boolean = true): Value? = try { presetResolver(id, edits)?.resolve(key) } catch (_: Throwable) { null }

    /** [key] as preset [id]'s files hold it (its source with it), or null. */
    fun presetEntry(id: String, key: String): Entry? {
        val c = presetChains.getOrPut(id) { try { chainOf(PRESETS, id) } catch (_: Throwable) { return null } }
        for (t in c.asReversed()) t.entries[key]?.let { return it }
        return null
    }

    /** Number [k] as animation preset [id] gives it (with the user's edits of it, or [edits] false: the preset's own). */
    fun previewNumber(id: String, k: NumberKey, edits: Boolean = true): Float? =
        (try { presetResolver(id, edits)?.resolve(k.name) } catch (_: Throwable) { null } as? Value.Number)?.v

    /** Animation preset [id] resolved on its own (its files, and with [edits] the user's edits of it); cached until a change. */
    private fun presetResolver(id: String, edits: Boolean): Resolver? = (if (edits) motionPreviews else motionOwn).getOrPut(id) {
        val c = presetChains.getOrPut(id) { try { chainOf(PRESETS, id) } catch (_: Throwable) { return null } }
        Resolver(c.map { it.entries } + if (edits) listOf(LinkedHashMap(motionEdits(id))) else emptyList())
    }

    /**
     * Presets' files as read (with the ones they are built on), kept until the files are read again ([reload]): a design
     * change makes the previews again from them, and parsing three preset files in a frame of Apply's reveal cost it.
     */
    private val presetChains = HashMap<String, List<Theme>>()

    private val motionPreviews = HashMap<String, Resolver>()
    private val motionOwn = HashMap<String, Resolver>()
    private val savedEdits = HashMap<String, Map<String, Entry>>()

    /** The user's edits of animation preset [id] (the active one's as they are now, another's as saved). */
    fun motionEdits(id: String): Map<String, Entry> = if (id == motionId) LinkedHashMap(motionUser) else savedEdits.getOrPut(id) {
        try {
            val f = File(appCtx.filesDir, motionEditsFile(id))
            if (f.exists()) Theme.parse(f.readText()).entries.filterKeys(::isMotion) else emptyMap()
        } catch (_: Throwable) { emptyMap() }
    }

    /**
     * The user set [key] of animation preset [id] to [v] (null, or the preset's own value: back to the preset's), active or
     * not (the settings' Apply): saved at once; for the active preset every surface draws again.
     */
    fun setMotionEdit(id: String, key: String, v: Value?) {
        if (!isMotion(key)) return
        val own = try { presetResolver(id, edits = false)?.resolve(key) } catch (_: Throwable) { null }
        val value = v?.takeIf { it != own }
        if (id == motionId) { if (value == null) reset(key) else set(key, value); return }
        val have = LinkedHashMap(motionEdits(id))
        if (value == null) { if (have.remove(key) == null) return } else have[key] = Entry(value, Provenance.User)
        writeEdits(File(appCtx.filesDir, motionEditsFile(id)), have)
        forgetPreset(id)
    }

    /** Every edit of animation preset [id] forgotten: it is as it comes again. */
    fun clearMotionEdits(id: String) {
        if (id == motionId) {
            if (motionUser.isEmpty()) return
            motionUser.clear()
            changed(structural = false)
            return
        }
        try { File(appCtx.filesDir, motionEditsFile(id)).delete() } catch (_: Throwable) { }
        forgetPreset(id)
    }

    private fun forgetPreset(id: String) { motionPreviews.remove(id); savedEdits.remove(id) }

    /**
     * Loads theme [id] (with the ones it is built on) and its edits, if the result has every token the code reads, of the
     * same kind as [DEFAULT_THEME]'s (a missing or mistyped token would fail where it is drawn); else nothing changes.
     */
    private fun load(id: String): Boolean {
        val l = prepare(id) ?: return false
        chain = l.chain
        user.clear()
        user.putAll(l.edits)
        rebuild()
        return true
    }

    /** Theme [id] read and checked (as [load] uses it), not made active; null if it cannot be used (logged). */
    private fun prepare(id: String): Loaded? {
        val c = (try { chainOf(THEMES, id) } catch (t: Throwable) { AppLog.log("[design] theme '$id' cannot be used: ${t.message}"); return null })
            .map { t -> if (t.entries.keys.none(::isMotion)) t else {
                // Motion is the animation preset's: a theme's own (an older theme file) is not used.
                AppLog.log("[design] theme '${t.name}': its motion tokens are not used (motion belongs to an animation preset)")
                Theme(t.name, t.author, t.version, t.entries.filterKeys { !isMotion(it) }, t.extends, t.materialYou.filterKeys { !isMotion(it) })
            } }
        val edits = try {
            val f = File(appCtx.filesDir, userFile(id))
            if (f.exists()) Theme.parse(f.readText()).entries.filterKeys { !isMotion(it) } else emptyMap()
        } catch (t: Throwable) { AppLog.log("[design] edits of '$id' unreadable (${t.message}): ignored"); emptyMap() }
        // Checked with and without its Material You section (either may be shown).
        for (my in listOf(false, true)) {
            val layers = layersOf(c, edits, my)
            val problem = if (id == DEFAULT_THEME && c.size == 1) checkResolves(layers) else checkAgainstDefault(layers)
            if (problem != null) { AppLog.log("[design] theme '$id' cannot be used" + (if (my) " in wallpaper colours" else "") + ": $problem"); return null }
        }
        return Loaded(id, c, edits)
    }

    // ------------------------------------------------------------------ a theme per element

    /** The theme [e] follows of its own (null: the active theme, as everything does by default). */
    fun elementTheme(e: dev.launcher.app.layout.Element): String? = elementIds[e]

    /** The name of the theme [e] is drawn in. */
    fun elementThemeName(e: dev.launcher.app.layout.Element): String =
        elementIds[e]?.let { elementThemes[it]?.chain?.lastOrNull()?.name } ?: themeName

    /**
     * [e] follows theme [id] from now on (remembered), or the active theme again (null); false if [id] cannot be used (the
     * reason is logged), nothing changes then. Only [e] changes: its namespace resolves in that theme ([TokenParts]).
     */
    fun setElementTheme(e: dev.launcher.app.layout.Element, id: String?): Boolean {
        if (id != null && elementThemes[id] == null) {
            val l = prepare(id) ?: return false
            elementThemes[id] = l
        }
        if (elementIds[e] == id) return true
        if (id == null) elementIds.remove(e) else elementIds[e] = id
        elementThemes.keys.retainAll(elementIds.values.toSet())
        saveElements()
        rebuild()
        changedOutside("${e.title}: " + (id?.let { "theme '${elementThemeName(e)}'" } ?: "follows the theme again"))
        return true
    }

    private fun loadElements() {
        val f = File(appCtx.filesDir, ELEMENTS)
        if (!f.exists()) return
        try {
            val o = org.json.JSONObject(f.readText())
            for (k in o.keys()) {
                val e = dev.launcher.app.layout.Element.of(k) ?: continue
                val id = o.getString(k)
                val l = elementThemes[id] ?: prepare(id) ?: continue   // a theme that is gone: the element follows again
                elementThemes[id] = l
                elementIds[e] = id
            }
        } catch (t: Throwable) { AppLog.log("[design] element themes unreadable (${t.message}): everything follows the theme") }
        if (elementIds.isNotEmpty()) AppLog.log("[design] own themes: " + elementIds.entries.joinToString { "${it.key.id}=${it.value}" })
    }

    private fun saveElements() = try {
        val f = File(appCtx.filesDir, ELEMENTS)
        f.parentFile?.mkdirs()
        val o = org.json.JSONObject()
        for ((e, id) in elementIds) o.put(e.id, id)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    } catch (t: Throwable) { AppLog.log("[design] saving the element themes failed: ${t.message}") }

    /**
     * Loads animation preset [id] (with the ones it is built on) and its edits, if it holds motion tokens only and gives
     * every one the code reads, of the same kind as [DEFAULT_MOTION]'s; else nothing changes.
     */
    private fun loadMotion(id: String): Boolean {
        val c = try { chainOf(PRESETS, id) } catch (t: Throwable) { AppLog.log("[design] animation preset '$id' cannot be used: ${t.message}"); return false }
        val stray = c.flatMap { it.entries.keys }.filterNot(::isMotion)
        if (stray.isNotEmpty()) { AppLog.log("[design] animation preset '$id' cannot be used: it holds tokens that are not motion (${stray.take(3).joinToString()})"); return false }
        val edits = try {
            val f = File(appCtx.filesDir, motionEditsFile(id))
            if (f.exists()) Theme.parse(f.readText()).entries.filterKeys(::isMotion) else emptyMap()
        } catch (t: Throwable) { AppLog.log("[design] edits of preset '$id' unreadable (${t.message}): ignored"); emptyMap() }
        val layers = c.map { it.entries } + listOf(LinkedHashMap(edits))
        val problem = checkResolves(layers) ?: if (id == DEFAULT_MOTION && c.size == 1) null else {
            val base = readFile(PRESETS, DEFAULT_MOTION)
            if (base == null) "the default animation preset is missing" else ThemeCheck.against(base.entries, layers)
        }
        if (problem != null) { AppLog.log("[design] animation preset '$id' cannot be used: $problem"); return false }
        motionChain = c
        motionUser.clear()
        motionUser.putAll(edits)
        rebuild()
        return true
    }

    private fun motionEditsFile(id: String) = "design/motion-edits-$id.json"

    /**
     * Earlier builds kept motion in the theme, so a user's motion edits are in a theme's edits: moved (once) into the
     * default preset's edits, where they apply as before.
     */
    private fun moveMotionEdits() {
        val dir = File(appCtx.filesDir, "design")
        val files = dir.listFiles()?.filter { it.name == "user.json" || (it.name.startsWith("user-") && it.name.endsWith(".json")) } ?: return
        for (f in files) try {
            val edits = Theme.parse(f.readText()).entries
            val moving = edits.filterKeys(::isMotion)
            if (moving.isEmpty()) continue
            val target = File(appCtx.filesDir, motionEditsFile(DEFAULT_MOTION))
            val have = if (target.exists()) Theme.parse(target.readText()).entries else emptyMap()
            target.writeText(Theme.write("user", LinkedHashMap(moving).apply { putAll(have) }))
            f.writeText(Theme.write("user", edits.filterKeys { !isMotion(it) }))
            AppLog.log("[design] ${moving.size} motion edits moved from ${f.name} to the animation preset's")
        } catch (t: Throwable) { AppLog.log("[design] moving motion edits from ${f.name} failed: ${t.message}") }
    }

    /** [id] and the files it is built on (themes or animation presets, in [folder]), the base first. */
    private fun chainOf(folder: String, id: String): List<Theme> {
        val out = ArrayList<Theme>()
        val seen = HashSet<String>()
        var cur: String? = id
        while (cur != null) {
            if (!seen.add(cur)) throw IllegalStateException("themes built on each other in a loop ($cur)")
            if (out.size >= MAX_EXTENDS) throw IllegalStateException("built on too many themes")
            val t = readFile(folder, cur) ?: throw IllegalStateException("'$cur' not found in $folder")
            out.add(0, t)
            cur = t.extends
        }
        return out
    }

    /** The file [id] in [folder] (themes or motion), from `files/<folder>/` or the app's own; null if none or unreadable. */
    private fun readFile(folder: String, id: String): Theme? = try {
        val f = File(appCtx.filesDir, "$folder/$id.json")
        val text = if (f.exists()) f.readText() else appCtx.assets.open("$folder/$id.json").bufferedReader().use { it.readText() }
        Theme.parse(text)
    } catch (t: Throwable) {
        if (t !is java.io.FileNotFoundException) AppLog.log("[design] $folder file '$id' unreadable: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun checkResolves(layers: List<Map<String, Entry>>): String? {
        val r = Resolver(layers)
        for (k in r.keys()) try { r.resolve(k) } catch (t: Throwable) { return t.message }
        return null
    }

    private fun checkAgainstDefault(layers: List<Map<String, Entry>>): String? {
        checkResolves(layers)?.let { return it }
        val base = readFile(THEMES, DEFAULT_THEME) ?: return "the default theme is missing"
        return ThemeCheck.against(base.entries, layers)
    }

    private fun userFile(id: String) = if (id == DEFAULT_THEME) "design/user.json" else "design/user-$id.json"

    private fun layersOf(c: List<Theme>, edits: Map<String, Entry>, materialYou: Boolean) = ThemeLayers.of(c, edits, materialYou)

    /** The theme's layers and the animation preset's (their keys never meet: motion.* is only the preset's). */
    private fun rebuild() {
        val layers = layersOf(chain, user, ThemeLayers.fromWallpaper(chain, user)) + motionChain.map { it.entries } + listOf(LinkedHashMap(motionUser))
        state = State(Resolver(layers) { name -> paletteColor(name) })
        scaledText.clear()
        followYouSettings()
        previews.clear()
        ownColors = null
        motionPreviews.clear()
        motionOwn.clear()
        savedEdits.clear()
        val byTheme = HashMap<String, State>()
        parts = elementIds.mapNotNull { (e, id) ->
            val l = elementThemes[id] ?: return@mapNotNull null
            e to byTheme.getOrPut(id) {
                State(Resolver(layersOf(l.chain, l.edits, ThemeLayers.fromWallpaper(l.chain, l.edits))) { name -> paletteColor(name) }, bound = true)
            }
        }.toMap()
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

    /**
     * The palette colour [name] (an `android.R.color` field's name, `system_primary_dark`) in the chosen Material You style
     * and intensity ([MaterialYou]), cached until the palette, the style or the intensity changes.
     */
    private fun paletteColor(name: String): Int? = palette.getOrPut(name) { youColor(name, myChoice) ?: NO_COLOR }.takeIf { it != NO_COLOR }

    /** Palette colour [name] as [choice] would make it (the settings show a choice before it is applied). */
    fun previewPalette(name: String, choice: MaterialYou.Choice): Int? = youColor(name, choice)

    /** The wallpaper's main colour (what "wallpaper" makes the palette from), if there is one. */
    fun wallpaperSeed(): Int? = seedColor()

    @Volatile private var myChoice = MaterialYou.Choice(MaterialYou.Style.SYSTEM, 1f)
    @Volatile private var seed: Int? = null
    private val schemes = java.util.concurrent.ConcurrentHashMap<String, MaterialYou.Scheme>()

    private fun youColor(name: String, c: MaterialYou.Choice): Int? {
        val own = MaterialYou.paletteOf(name)?.let { it in c.keys } == true
        // The phone's own palette, unless the user's colour or a key colour of theirs makes this one.
        if (c.style == MaterialYou.Style.SYSTEM && c.seed == null && !own) return systemColor(name)?.let { MaterialYou.scaleChroma(it, c.intensity) }
        // A colour of one's own in "the phone's" style: the tonal one (the phone's palette comes from its wallpaper only).
        val style = if (c.style == MaterialYou.Style.SYSTEM) MaterialYou.Style.TONAL else c.style
        val s = c.seed ?: seedColor() ?: return systemColor(name)
        val scheme = schemes.getOrPut("${c.id}/$s") { MaterialYou.scheme(s, style, c.intensity, c.keys) }
        return MaterialYou.color(scheme, name) ?: systemColor(name)
    }

    /** The wallpaper's main colour (the system's reading of it), else the system palette's primary. */
    private fun seedColor(): Int? = seed ?: (try {
        if (android.os.Build.VERSION.SDK_INT >= 27)
            android.app.WallpaperManager.getInstance(appCtx).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
        else null
    } catch (_: Throwable) { null } ?: systemColor("system_accent1_500"))?.also { seed = it }

    /** The style and intensity in the design now (after a rebuild): the palette made again if they changed. */
    private fun followYouSettings() {
        fun choice(key: ChoiceKey) = (try { value(key.name) } catch (_: Throwable) { null } as? Value.Choice)?.option
        val st = MaterialYou.Style.of(choice(COLOR_STYLE))
        val k = (try { value(COLOR_INTENSITY.name) } catch (_: Throwable) { null } as? Value.Number)?.v ?: 1f
        val keys = COLOR_KEYS.mapNotNull { (p, key) -> hexColor(choice(key))?.let { p to it } }.toMap()
        val c = MaterialYou.Choice(st, k, hexColor(choice(COLOR_SEED)), keys)
        if (c != myChoice) { myChoice = c; palette.clear() }
    }

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
    private var scaleAt = ""

    /**
     * The phone's display size or font size is now [densityDpi] and [fontScale] (any configuration change: only a change of
     * these counts): every surface is built again at the new scale.
     */
    fun onScale(densityDpi: Int, fontScale: Float) {
        val stamp = "$densityDpi/$fontScale"
        if (stamp == scaleAt) return
        val first = scaleAt.isEmpty()
        scaleAt = stamp
        if (first || chain.isEmpty()) return
        rebuild()
        changedOutside("the display size or the font size changed ($stamp)", structural = true)
    }

    fun onConfiguration() {
        if (chain.isEmpty()) return
        val stamp = paletteStamp()
        if (stamp == paletteAt) return
        paletteAt = stamp
        seed = null
        schemes.clear()
        palette.clear()
        rebuild()
        changedOutside("the wallpaper's colours changed", structural = false)
    }

    private fun logPalette() {
        val names = android.R.color::class.java.fields.map { it.name }.filter { it.startsWith("system_") }.sorted()
        val out = names.mapNotNull { n -> systemColor(n)?.let { "$n=#" + String.format("%08x", it).substring(2) } }
        out.chunked(12).forEach { AppLog.log("[design] palette " + it.joinToString(" ")) }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    // ------------------------------------------------------------------ reading

    private fun value(key: String): Value {
        val p = parts
        if (p.isEmpty()) return state.value(key)
        val e = TokenParts.of(key) ?: return state.value(key)
        return (p[e] ?: state).value(key)
    }

    /** [k]'s colour at the current appearance (ARGB); palette colours looked up (cached). */
    fun color(k: ColorKey): Int {
        val v = value(k.name) as? Value.Color ?: throw IllegalStateException("token $k is not a colour")
        if (!v.dynamic) return Appearance.mix(v.light, v.dark)
        return resolver.pair(v).let { (l, d) -> Appearance.mix(l, d) }
    }

    /** A colour value's light and dark ARGB, its palette references looked up (the token editor shows them). */
    fun pair(v: Value.Color): Pair<Int, Int> = resolver.pair(v)

    /**
     * [k]'s colour as the active theme has it itself, the wallpaper's colours (Material You) left out, at the current
     * appearance: what a colour that must stay its own hue starts from (the settings' parts, harmonised with the palette).
     */
    fun ownColor(k: ColorKey): Int {
        val r = ownColors ?: Resolver(layersOf(chain, user, false)) { name -> paletteColor(name) }.also { ownColors = it }
        val v = try { r.resolve(k.name) as? Value.Color } catch (_: Throwable) { null } ?: return color(k)
        return r.pair(v).let { (l, d) -> Appearance.mix(l, d) }
    }

    @Volatile private var ownColors: Resolver? = null

    /**
     * A material's colour (a literal or a token) at the current appearance. A token is read as any other, by its own
     * namespace (a reference made in code, as Control Center's lit wells, follows its element's theme).
     */
    fun color(c: ColorValue): Int =
        if (c is ColorValue.Ref) color(ColorKey(c.key)) else resolver.colorPair(c).let { (l, d) -> Appearance.mix(l, d) }

    /** A blend token ([Blend]'s name; NORMAL if it is not one). */
    fun blend(k: ChoiceKey): Blend = try { Blend.valueOf(choice(k)) } catch (_: IllegalArgumentException) { Blend.NORMAL }

    /** [k]'s number in its own unit (points for lengths). */
    fun num(k: NumberKey): Float = (value(k.name) as? Value.Number ?: throw IllegalStateException("token $k is not a number")).v

    /** [k] (a length in points) in pixels, at [unitPx] pixels per point ([Scale.unitPx]). */
    fun pt(k: NumberKey, unitPx: Float): Float = num(k) * unitPx

    /**
     * [k]'s curve as code runs it: an animation role's at the preset's overall speed and bounce
     * ([dev.launcher.app.motion.MotionAdjust]; the token editor shows and edits the role's own, [resolved]).
     */
    fun curve(k: CurveKey): Curve {
        val s = state
        s.curves[k.name]?.let { return it }
        val raw = (value(k.name) as? Value.CurveV ?: throw IllegalStateException("token $k is not a curve")).curve
        val c = if (!isMotion(k.name)) raw else {
            fun num(n: NumberKey) = (try { s.value(n.name) } catch (_: Throwable) { null } as? Value.Number)?.v ?: 1f
            // The preset's speed and bounce, and the moment's on top of them (docs/PLAN_SETTINGS_V2.md, V2).
            val m = dev.launcher.app.motion.Moments.of(k.name)
            dev.launcher.app.motion.MotionAdjust.apply(raw,
                dev.launcher.app.motion.MotionAdjust.speed(num(dev.launcher.app.motion.MotionTokens.ADJUST_SPEED), m?.let { num(it.speed) } ?: 1f),
                dev.launcher.app.motion.MotionAdjust.bounce(num(dev.launcher.app.motion.MotionTokens.ADJUST_BOUNCE), m?.let { num(it.bounce) } ?: 1f))
        }
        s.curves[k.name] = c
        return c
    }
    fun choice(k: ChoiceKey): String = (value(k.name) as? Value.Choice ?: throw IllegalStateException("token $k is not a choice")).option
    /**
     * Text style [k], at the phone's Font size where its part follows it (Apple's Dynamic Type: notifications, controls,
     * menus, the settings; not the status bar nor home's labels, as on iOS), up to [Scale.TEXT_MAX]: size, line height and
     * tracking together, so what is laid out by them grows with it.
     */
    fun text(k: TextKey): TextStyle = scaledText.getOrPut(k.name) {
        val s = (value(k.name) as? Value.Text ?: throw IllegalStateException("token $k is not a text style")).style
        val f = Scale.textScale(k.name)
        if (f == 1f) s else s.copy(sizePt = s.sizePt * f, lineHeightPt = s.lineHeightPt * f, trackingPt = s.trackingPt * f)
    }

    private val scaledText = java.util.concurrent.ConcurrentHashMap<String, TextStyle>()

    /** The app's context, for what the design system reads of the phone (its font size). */
    internal val context: Context get() = appCtx
    fun material(k: MaterialKey): Material = (value(k.name) as? Value.Mat ?: throw IllegalStateException("token $k is not a material")).material

    // ------------------------------------------------------------------ the token editor's side

    /** Every token key of the theme (and of the user's edits), sorted. */
    fun keys(): List<String> = resolver.keys().sorted()

    /** [key] as the theme (or, for motion, the animation preset) defines it, and as the user edited it (null: not edited). */
    fun themeEntry(key: String): Entry? {
        for (t in (if (isMotion(key)) motionChain else chain).asReversed()) t.entries[key]?.let { return it }
        return null
    }
    fun userEntry(key: String): Entry? = if (isMotion(key)) motionUser[key] else user[key]
    fun entry(key: String): Entry? = resolver.entry(key)
    fun resolved(key: String): Value = value(key)

    /** The user changed [key] to [v] (saved at once, every surface draws again). */
    fun set(key: String, v: Value) {
        (if (isMotion(key)) motionUser else user)[key] = Entry(v, Provenance.User)
        changed(structural = !isMotion(key))
    }

    /** Back to the theme's (or the animation preset's) value. */
    fun reset(key: String) {
        if ((if (isMotion(key)) motionUser else user).remove(key) != null) changed(structural = !isMotion(key))
    }

    fun resetAll() {
        if (user.isEmpty() && motionUser.isEmpty()) return
        user.clear()
        motionUser.clear()
        changed()
    }

    private fun changed(structural: Boolean = true) {
        rebuild()
        save()
        if (batching > 0) { batchStructural = (batchStructural ?: false) || structural; return }
        version++
        if (structural) structure++
        val run = Runnable { for (l in listeners.toList()) l() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private fun save() {
        for ((name, edits) in listOf(userFile(activeId) to user, motionEditsFile(motionId) to motionUser)) writeEdits(File(appCtx.filesDir, name), edits)
    }

    private fun writeEdits(f: File, edits: Map<String, Entry>) {
        try {
            if (edits.isEmpty() && !f.exists()) return
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Theme.write("user", edits))
            tmp.renameTo(f)
        } catch (t: Throwable) {
            AppLog.log("[design] saving the edits failed: ${t.message}")
        }
    }
}

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

/**
 * How points become pixels: the policy (`sys.scale.policy`, the theme's default, the user's choice in the settings' Size).
 * "adaptive": a phone's short side is `sys.scale.reference-width` points wide (iOS 27's iPhone: 402 pt; on the S24 an
 * element comes out the size it is on an iPhone 17 Pro), but a point is never larger than Android's own dp at the phone's
 * default display size (a tablet or a foldable shows more, as an iPad does, rather than everything larger), times the
 * phone's Display size setting (how far it is from the default); text follows its Font size. "custom": the same base times
 * the user's own sizes, `sys.scale.size` for everything and `sys.scale.text` for text (1: an iPhone's), whatever the
 * phone's settings. "reference-width": the short side is always that many points, times the user's sizes; "density": one
 * point is one Android dp.
 */
object Scale {
    val POLICY = ChoiceKey("sys.scale.policy")
    val REFERENCE_WIDTH = NumberKey("sys.scale.reference-width")
    /** The user's own size of everything (policy "custom"): a factor of an iPhone's. */
    val SIZE = NumberKey("sys.scale.size")
    /** The user's own size of text (policy "custom"): a factor of an iPhone's. */
    val TEXT = NumberKey("sys.scale.text")

    const val SIZE_MIN = 0.7f
    const val SIZE_MAX = 1.3f
    const val TEXT_MIN = 0.75f
    /** The most text grows (the layouts' rows and fields are made for this much). */
    const val TEXT_MAX = 1.3f
    /** Parts whose text keeps its size whatever the phone's Font size (iOS: the status bar and home's labels). */
    private val FIXED_TEXT = listOf("comp.statusbar.", "comp.home.")
    /** Parts whose text keeps its size whatever the user's own text size (the status bar: its height is the phone's). */
    private const val BAR_TEXT = "comp.statusbar."

    fun policy(): String = try { Design.choice(POLICY) } catch (_: Throwable) { "adaptive" }
    private fun factor(k: NumberKey, min: Float, max: Float) = (try { Design.num(k) } catch (_: Throwable) { 1f }).coerceIn(min, max)
    /** The user's own sizes as applied (1 when the policy does not use them). */
    fun sizeFactor(): Float = factor(SIZE, SIZE_MIN, SIZE_MAX)
    fun textFactor(): Float = factor(TEXT, TEXT_MIN, TEXT_MAX)

    fun unitPx(ctx: Context, shortSidePx: Int): Float = unitFor(ctx, shortSidePx, policy(), sizeFactor())

    /** One point in pixels under [policy] with the user's size [size] (the settings measure a choice before it is applied). */
    fun unitFor(ctx: Context, shortSidePx: Int, policy: String, size: Float): Float {
        val base = minOf(shortSidePx / Design.num(REFERENCE_WIDTH), stable())
        return when (policy) {
            "density" -> ctx.resources.displayMetrics.density
            "reference-width" -> shortSidePx / Design.num(REFERENCE_WIDTH) * size
            "custom" -> base * size
            else -> base * phoneZoom(ctx)
        }
    }

    /** The phone's own density at its default display size (px per dp). */
    private fun stable() = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE / 160f

    /** How far the phone's Display size setting is from its default (1: the default; 0.875: everything 12.5 % smaller). */
    fun phoneZoom(ctx: Context): Float = ctx.resources.displayMetrics.density / stable()

    /** The phone's Font size as text follows it (within what the layouts are made for). */
    fun phoneText(ctx: Context): Float = ctx.resources.configuration.fontScale.coerceIn(0.85f, TEXT_MAX)

    /** How much text style [name] is grown or shrunk (1: an iPhone's size). */
    fun textScale(name: String): Float = when (policy()) {
        "adaptive" -> if (FIXED_TEXT.any { name.startsWith(it) }) 1f else try { phoneText(Design.context) } catch (_: Throwable) { 1f }
        "density" -> 1f
        else -> if (name.startsWith(BAR_TEXT)) 1f else textFactor()
    }

    /** How much home's own text (its labels, the App Library's) is grown: the user's own text size only (not the phone's: iOS). */
    fun homeText(): Float = when (policy()) {
        "adaptive", "density" -> 1f
        else -> textFactor()
    }

    /** One point in pixels on [ctx]'s display (its short side), for views that are not the size of the screen. */
    fun unitPx(ctx: Context): Float {
        val dm = ctx.resources.displayMetrics
        return unitPx(ctx, minOf(dm.widthPixels, dm.heightPixels))
    }

}
