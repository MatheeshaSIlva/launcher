package dev.launcher.app.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.launcher.app.AppLog
import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.Design

/**
 * Typefaces of the active theme (B2d). The theme names two families, `sys.font.text` (body and label text) and
 * `sys.font.display` (large titles), and a text style may name any other ([FontFamily]). iOS 27 uses Inter (SIL Open Font
 * License, bundled in assets/fonts): SF Pro is licensed only for Apple platforms. Inter's optical-size axis plays the part
 * of SF's Text/Display split: small text uses the text cut, large titles the display cut.
 *
 * A Google font not yet on the phone is downloaded in the background; until it arrives the system's sans stands in, and
 * on arrival every surface that listens to the design draws again. Home is built again on a change of fonts (B4a);
 * paints made once elsewhere follow through [Followers] (B4b).
 */
object Fonts {
    val TEXT = ChoiceKey("sys.font.text")
    val DISPLAY = ChoiceKey("sys.font.display")

    private lateinit var ctx: Context
    private val cache = HashMap<String, Typeface>()
    private val warned = HashSet<String>()

    fun init(context: Context) { ctx = context.applicationContext }

    /** Body and label text in the theme's text family (Inter: optical size 14). */
    fun text(weight: Int = 400): Typeface = get(theme(TEXT), weight, 14)

    /** Large titles in the theme's display family (Inter: optical size 32). */
    fun display(weight: Int = 600): Typeface = get(theme(DISPLAY), weight, 32)

    /** A text style's [family] ("text", "display" or a family id) at [weight], for text [sizePt] points large. */
    fun of(family: String, weight: Int, sizePt: Float): Typeface = when (family) {
        "text" -> text(weight)
        "display" -> display(weight)
        else -> get(parse(family), weight, sizePt.toInt().coerceIn(14, 32))
    }

    // ------------------------------------------------------------------ paints that follow (B4b)

    /**
     * Paints that keep the theme's fonts: given their font with [text] / [display] (in place of `typeface = Fonts.text(w)`)
     * and given it again by [refresh] when the design's structure changed (another theme, an edit, a font that arrived).
     * With a [view] that happens on the view's own thread while it is attached, then [onChange] runs (text measured again);
     * without one, the owner calls [refresh].
     */
    class Followers(private val view: android.view.View? = null, private val onChange: () -> Unit = { view?.invalidate() }) {
        private val paints = ArrayList<Triple<android.graphics.Paint, Boolean, Int>>()
        private var at = Design.structure
        private val listener: () -> Unit = { view?.handler?.post { if (refresh()) onChange() } }

        init {
            view?.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) {
                    Design.addListener(listener)
                    if (refresh()) onChange()
                }
                override fun onViewDetachedFromWindow(v: android.view.View) = Design.removeListener(listener)
            })
        }

        /** [p] in the theme's text family at [weight], now and after every change. */
        fun text(p: android.graphics.Paint, weight: Int) { paints += Triple(p, false, weight); p.typeface = Fonts.text(weight) }

        /** [p] in the theme's display family at [weight], now and after every change. */
        fun display(p: android.graphics.Paint, weight: Int) { paints += Triple(p, true, weight); p.typeface = Fonts.display(weight) }

        /** Every paint given the theme's fonts again if the design changed since; true if it did. */
        fun refresh(): Boolean {
            val s = Design.structure
            if (s == at) return false
            at = s
            for ((p, d, w) in paints) p.typeface = if (d) Fonts.display(w) else Fonts.text(w)
            return true
        }
    }

    // ------------------------------------------------------------------ the theme's families

    private var themeAt = -1
    private var textFamily: FontFamily = FontFamily.Inter
    private var displayFamily: FontFamily = FontFamily.Inter

    /** The family the theme's [key] names, read again only when the design changed. */
    @Synchronized
    private fun theme(key: ChoiceKey): FontFamily {
        val v = Design.version
        if (v != themeAt) {
            themeAt = v
            textFamily = parse(read(TEXT))
            displayFamily = parse(read(DISPLAY))
        }
        return if (key === TEXT) textFamily else displayFamily
    }

    private fun read(key: ChoiceKey): String = try { Design.choice(key) } catch (_: Throwable) { FontFamily.Inter.id }

    /** [id]'s family; one that names none is Inter (logged once). */
    private fun parse(id: String): FontFamily = FontFamily.parse(id) ?: run {
        if (synchronized(warned) { warned.add(id) }) AppLog.log("[fonts] '$id' is not a font family (inter, system, system-serif, system-mono, google:Name): Inter")
        FontFamily.Inter
    }

    // ------------------------------------------------------------------ typefaces

    @Synchronized
    private fun get(family: FontFamily, weight: Int, opsz: Int): Typeface {
        val w = weight.coerceIn(1, 1000)
        val key = "$family/$w/" + if (family is FontFamily.Inter) opsz else 0
        cache[key]?.let { return it }
        val tf = when (family) {
            is FontFamily.Inter -> inter(w, opsz)
            is FontFamily.System -> Typeface.create(when (family.generic) {
                FontFamily.Generic.SANS -> Typeface.DEFAULT
                FontFamily.Generic.SERIF -> Typeface.SERIF
                FontFamily.Generic.MONO -> Typeface.MONOSPACE
            }, w, false)
            is FontFamily.Google -> google(family.name, w) ?: return fallback(w)   // not kept: the font may still arrive
        }
        cache[key] = tf
        return tf
    }

    private fun inter(weight: Int, opsz: Int): Typeface = try {
        Typeface.Builder(ctx.assets, "fonts/InterVariable.ttf")
            .setFontVariationSettings("'wght' $weight, 'opsz' $opsz")
            .setWeight(weight)
            .build() ?: fallback(weight)
    } catch (_: Throwable) {
        fallback(weight)
    }

    private fun fallback(weight: Int): Typeface = Typeface.create(Typeface.SANS_SERIF, weight.coerceIn(1, 1000), false)

    // ------------------------------------------------------------------ Google Fonts

    /** Downloads run here, one at a time. */
    private val loader by lazy { Handler(HandlerThread("fonts").apply { start() }.looper) }
    /** Downloads asked for or running ("Name/weight"), and when one last failed (retried a minute later). */
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    /** [name] at [weight] if it is on the phone; else null, and it is fetched. Google's static cuts step by 100. */
    private fun google(name: String, weight: Int): Typeface? {
        val w = ((weight + 50) / 100 * 100).coerceIn(100, 900)
        val file = GoogleFonts.file(ctx, name, w)
        if (file.exists()) GoogleFonts.load(file, w)?.let { return it }
        fetch(name, w)
        return null
    }

    private fun fetch(name: String, weight: Int) {
        val key = "$name/$weight"
        val now = SystemClock.uptimeMillis()
        if (pending.contains(key) || (failedAt[key]?.let { now - it < 60_000 } == true)) return
        pending.add(key)
        loader.post {
            val t0 = SystemClock.uptimeMillis()
            val why = try { GoogleFonts.download(ctx, name, weight, GoogleFonts.file(ctx, name, weight)) } catch (t: Throwable) { t.toString() }
            if (why != null) AppLog.log("[fonts] Google font '$name' $weight: $why")
            else AppLog.log("[fonts] Google font '$name' $weight arrived (${SystemClock.uptimeMillis() - t0} ms)")
            // Surfaces draw again once, when the last font asked for is in (weights are asked for together).
            val notify = synchronized(this) {
                pending.remove(key)
                if (why != null) failedAt[key] = SystemClock.uptimeMillis() else arrived.add(name)
                pending.isEmpty() && arrived.isNotEmpty()
            }
            if (notify) {
                val names = synchronized(this) { arrived.toList().also { arrived.clear() } }
                Design.fontArrived(names.joinToString(", "))
            }
        }
    }

    /** Families that arrived since surfaces last drew again. */
    private val arrived = LinkedHashSet<String>()
}
