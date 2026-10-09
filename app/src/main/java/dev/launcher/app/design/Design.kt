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
    /** The resolver and its cache, replaced as a whole on a change (surfaces read tokens on their own threads). */
    private class State(val resolver: Resolver) { val cache = java.util.concurrent.ConcurrentHashMap<String, Value>() }
    @Volatile private var state = State(Resolver(emptyList()))
    private val resolver get() = state.resolver
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
        // Over adb, for fast iteration (and tests): the edits file is read again and everything drawn with tokens redraws.
        //   adb shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app
        // Senders must hold DUMP (adb's shell does; other apps cannot).
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) = reloadEdits()
        }
        val f = android.content.IntentFilter("dev.launcher.app.DESIGN_RELOAD")
        if (android.os.Build.VERSION.SDK_INT >= 33) appCtx.registerReceiver(r, f, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        else appCtx.registerReceiver(r, f, android.Manifest.permission.DUMP, null)
    }

    /** The edits file read again (written over adb): applied at once, as an edit in the token editor is. */
    fun reloadEdits() {
        val read = try {
            val f = File(appCtx.filesDir, USER)
            if (f.exists()) Theme.parse(f.readText()).entries else emptyMap()
        } catch (t: Throwable) {
            AppLog.log("[design] reload: edits unreadable (${t.message}): kept as they were"); return
        }
        user.clear()
        user.putAll(read)
        rebuild()
        version++
        AppLog.log("[design] reloaded: ${user.size} edited")
        val run = Runnable { for (l in listeners.toList()) l() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private fun rebuild() {
        state = State(Resolver(listOf(base?.entries ?: emptyMap(), LinkedHashMap(user))))
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
