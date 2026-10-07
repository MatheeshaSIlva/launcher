package dev.launcher.app.design

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.launcher.app.AppLog
import dev.launcher.app.theme.Appearance
import java.io.File

/**
 * The live design system: the active theme (`assets/themes/<name>.json`) with the user's edits over it
 * (`files/design/user.json`, written by the token editor), resolved for drawing. Colours are blended at the current
 * appearance ([Appearance.dark], so a light/dark change crossfades every token on the same frames); numbers come in their
 * own unit ([pt] turns points into pixels by the scaling policy).
 *
 * Changing a token bumps [version] and calls the listeners (on the main thread): views that drew with tokens draw again.
 * Resolved values are cached until the next change; reading a token in a draw call is a map lookup.
 */
object Design {
    private const val BASE = "themes/ios27.json"
    private const val USER = "design/user.json"

    private lateinit var appCtx: Context
    private var base: Theme? = null
    private val user = LinkedHashMap<String, Entry>()
    private var resolver = Resolver(emptyList())
    private val cache = HashMap<String, Value>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Bumped on every change of a token (views keyed on it draw again). */
    @Volatile var version = 0
        private set

    val themeName get() = base?.name ?: "none"

    /** Loads the theme and the user's edits (at app start, before anything draws). */
    fun init(ctx: Context) {
        if (base != null) return
        appCtx = ctx.applicationContext
        base = try {
            Theme.parse(ctx.assets.open(BASE).bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            AppLog.log("[design] theme $BASE failed: ${t.javaClass.simpleName}: ${t.message}")
            Theme("empty", "", "", emptyMap())
        }
        try {
            val f = File(ctx.filesDir, USER)
            if (f.exists()) user.putAll(Theme.parse(f.readText()).entries)
        } catch (t: Throwable) {
            AppLog.log("[design] user edits unreadable (${t.message}): ignored")
        }
        rebuild()
        AppLog.log("[design] theme '${base?.name}' ${base?.entries?.size} tokens, ${user.size} edited")
    }

    private fun rebuild() {
        resolver = Resolver(listOf(base?.entries ?: emptyMap(), user))
        cache.clear()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    // ------------------------------------------------------------------ reading

    private fun value(key: String): Value = cache.getOrPut(key) { resolver.resolve(key) }

    /** [k]'s colour at the current appearance (ARGB). */
    fun color(k: ColorKey): Int {
        val v = value(k.name) as? Value.Color ?: throw IllegalStateException("token $k is not a colour")
        return Appearance.mix(v.light, v.dark)
    }

    /** A material's colour (a literal or a token) at the current appearance. */
    fun color(c: ColorValue): Int = resolver.colorPair(c).let { (l, d) -> Appearance.mix(l, d) }

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
    fun themeEntry(key: String): Entry? = base?.entries?.get(key)
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
            val f = File(appCtx.filesDir, USER)
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
object Scale {
    val POLICY = ChoiceKey("sys.scale.policy")
    val REFERENCE_WIDTH = NumberKey("sys.scale.reference-width")

    fun unitPx(ctx: Context, shortSidePx: Int): Float = when (Design.choice(POLICY)) {
        "density" -> ctx.resources.displayMetrics.density
        else -> shortSidePx / Design.num(REFERENCE_WIDTH)
    }
}
