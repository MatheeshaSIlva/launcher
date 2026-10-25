package dev.launcher.app.design

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import dev.launcher.app.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Icon sets (`assets/icons/<id>.json`, or `files/icons/<id>.json` pushed through the inbox, which wins over the app's):
 * symbols as vector paths, drawn in place of the app's own (`res/drawable/sym_<name>.xml`, Material Symbols Rounded)
 * wherever a theme names the set (`sys.symbols`, read through each element's own token: `comp.cc.symbols` ...). Written by
 * `tools/icons/build_icon_set.py`, each symbol with its source. A name the set leaves out is drawn with the app's own;
 * `NAME.off` is the look of a control that is off (the shade crossfades the two as it turns on and off), or plays the
 * symbol's animation when the symbol has a `play` (B-036: [IconSet.motion], built on its first ask; [preparePlays] builds a
 * set's off the drawing thread).
 */
object IconSets {
    /** The app's own symbols: no file, every name drawn from its drawable. */
    const val OWN = "launcher"
    private const val DIR = "icons"

    /** One path of a symbol, in its set's box: filled ([fill]: how opaque) and/or stroked, maybe clipped. */
    class Layer(val path: Path, val fill: Float, val stroke: Float, val strokeAlpha: Float, val cap: Paint.Cap,
                val join: Paint.Join, val clips: List<Path>)

    /** A symbol: its paths in [box], drawn at [scale] of the size asked for (some are small symbols in a larger slot). */
    class Glyph(val box: RectF, val layers: List<Layer>, val scale: Float = 1f) {
        /** Draws it centred on [cx],[cy] with its box [size] px across, in [color] (its alpha fades it), with [paint]. */
        fun draw(c: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
            val s = size * scale / max(box.width(), box.height())
            val save = c.save()
            c.translate(cx, cy)
            c.scale(s, s)
            c.translate(-box.centerX(), -box.centerY())
            val a = (color ushr 24) / 255f
            for (l in layers) {
                val clipped = if (l.clips.isEmpty()) -1 else c.save().also { for (cp in l.clips) c.clipPath(cp) }
                if (l.fill > 0f) {
                    paint.style = Paint.Style.FILL
                    paint.color = withAlpha(color, a * l.fill)
                    c.drawPath(l.path, paint)
                }
                if (l.stroke > 0f && l.strokeAlpha > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = l.stroke
                    paint.strokeCap = l.cap
                    paint.strokeJoin = l.join
                    paint.color = withAlpha(color, a * l.strokeAlpha)
                    c.drawPath(l.path, paint)
                }
                if (clipped >= 0) c.restoreToCount(clipped)
            }
            c.restoreToCount(save)
        }
    }

    class IconSet(val id: String, val name: String, private val icons: JSONObject) {
        private val glyphs = HashMap<String, Glyph?>()

        /** Symbol [name] (an app symbol's name without `sym_`, maybe with `.off`), or null if the set leaves it out. */
        @Synchronized fun glyph(name: String): Glyph? = glyphs.getOrPut(name) {
            icons.optJSONObject(name)?.let { try { parse(it) } catch (t: Throwable) { AppLog.log("[icons] $id/$name: ${t.message}"); null } }
        }

        val names: List<String> get() = icons.keys().asSequence().toList().sorted()

        private val motions = ConcurrentHashMap<String, Any>()   // (a value: a SymbolMotion, or NO_PLAY)
        @Volatile private var prepared = false

        /**
         * Symbol [name]'s animation (its `play`), or null if it has none or cannot be read (logged). Built from the file's
         * data on the first ask and kept: a few hundred microseconds a symbol, so never first asked on a drawing thread
         * ([preparePlays] asks for them all ahead).
         */
        fun motion(name: String): SymbolMotion? {
            val hit = motions[name] ?: run {
                val built = icons.optJSONObject(name)?.optJSONObject("play")?.let {
                    try { SymbolMotion(it) } catch (t: Throwable) { AppLog.log("[icons] $id/$name: its play: ${t.message}"); null }
                }
                motions.putIfAbsent(name, built ?: NO_PLAY) ?: built ?: NO_PLAY
            }
            return hit as? SymbolMotion
        }

        internal fun preparePlays() {
            if (prepared) return
            val t0 = System.nanoTime()
            var n = 0
            for (name in names) if (motion(name) != null) n++
            prepared = true
            AppLog.log("[icons] $id: $n plays ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    private val NO_PLAY = Any()
    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "icon-plays").apply { isDaemon = true } } }

    /**
     * Reads set [id] (if not yet read) and builds every play of it, on a worker thread of its own: call it as soon as a
     * theme names the set, so that the first toggle of a tile builds nothing on a drawing thread. Nothing for the app's own
     * symbols, a set that cannot be read, or a set already prepared.
     */
    fun preparePlays(ctx: Context, id: String?) {
        if (id == null || id == OWN) return
        val app = ctx.applicationContext
        io.execute { try { get(app, id)?.preparePlays() } catch (t: Throwable) { AppLog.log("[icons] $id: plays: ${t.message}") } }
    }

    private val sets = ConcurrentHashMap<String, IconSet>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Set [id] (null for the app's own, or a set that cannot be read: its names then fall back to the app's own). */
    fun get(ctx: Context, id: String?): IconSet? {
        if (id == null || id == OWN || id in missing) return null
        sets[id]?.let { return it }
        val set = read(ctx, id)
        if (set == null) { missing += id; AppLog.log("[icons] no set '$id': the app's own symbols"); return null }
        sets[id] = set
        return set
    }

    @Volatile private var listed: List<Pair<String, String>>? = null

    /** The sets there are (id to name), the app's own first; read once (the settings ask in composition), again after [clear]. */
    fun list(ctx: Context): List<Pair<String, String>> = listed ?: readList(ctx).also { listed = it }

    private fun readList(ctx: Context): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        out[OWN] = "Launcher"
        val ids = (try { ctx.assets.list(DIR)?.toList() } catch (_: Throwable) { null }.orEmpty() +
            File(ctx.filesDir, DIR).listFiles()?.map { it.name }.orEmpty()).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
        for (id in ids.distinct()) get(ctx, id)?.let { out[id] = it.name }
        return out.toList()
    }

    /** Read again when next asked (a set pushed over adb shows with the next design reload). */
    fun clear() { sets.clear(); missing.clear(); listed = null }

    private fun read(ctx: Context, id: String): IconSet? = try {
        val t0 = System.nanoTime()
        val f = File(File(ctx.filesDir, DIR), "$id.json")
        val text = if (f.exists()) f.readText() else ctx.assets.open("$DIR/$id.json").bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        AppLog.log("[icons] $id: ${text.length / 1024} KB read and parsed in ${(System.nanoTime() - t0) / 1_000_000} ms")
        IconSet(id, o.optString("name", id), o.getJSONObject("icons"))
    } catch (_: Throwable) { null }

    private fun parse(o: JSONObject): Glyph {
        val b = o.getJSONArray("box")
        val box = RectF(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(),
            (b.getDouble(0) + b.getDouble(2)).toFloat(), (b.getDouble(1) + b.getDouble(3)).toFloat())
        val ls = o.getJSONArray("layers")
        val layers = ArrayList<Layer>(ls.length())
        for (i in 0 until ls.length()) {
            val l = ls.getJSONObject(i)
            val m = matrixOf(l.optJSONArray("m"))
            val path = pathOf(l.getString("d"), m)
            if (l.optInt("even") == 1) path.fillType = Path.FillType.EVEN_ODD
            val trimmed = l.optJSONArray("trim")?.let { trim(path, it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) } ?: path
            // A stroke grows with the transform its path took (VectorDrawable scales it by the matrix's scale).
            val scale = m?.let { val v = FloatArray(9).also { a -> it.getValues(a) }; sqrt(abs(v[0] * v[4] - v[1] * v[3])) } ?: 1f
            val clips = ArrayList<Path>()
            l.optJSONArray("clip")?.let { cs -> for (j in 0 until cs.length()) cs.getJSONObject(j).let { cp -> clips += pathOf(cp.getString("d"), matrixOf(cp.optJSONArray("m"))) } }
            layers += Layer(trimmed, if (l.has("a")) l.getDouble("a").toFloat() else 1f, l.optDouble("s", 0.0).toFloat() * scale,
                l.optDouble("sa", 1.0).toFloat(),
                when (l.optString("cap")) { "round" -> Paint.Cap.ROUND; "square" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT },
                when (l.optString("join")) { "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; else -> Paint.Join.MITER },
                clips)
        }
        return Glyph(box, layers, o.optDouble("scale", 1.0).toFloat())
    }

    internal fun pathOf(d: String, m: Matrix?): Path {
        val p = PathParser().parsePathString(d).toPath().asAndroidPath()
        if (m != null) p.transform(m)
        return p
    }

    private fun matrixOf(a: JSONArray?): Matrix? {
        if (a == null || a.length() != 6) return null
        return Matrix().apply { setValues(floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(),
            a.getDouble(3).toFloat(), a.getDouble(4).toFloat(), a.getDouble(5).toFloat(), 0f, 0f, 1f)) }
    }

    /** [path] from [start] to [end] of its whole length (every contour after another), shifted by [offset], as VectorDrawable trims. */
    private fun trim(path: Path, start: Float, end: Float, offset: Float): Path {
        val lengths = ArrayList<Float>()
        val pm = PathMeasure(path, false)
        do { lengths += pm.length } while (pm.nextContour())
        val total = lengths.sum()
        if (total <= 0f) return path
        var s = ((start + offset) % 1f + 1f) % 1f * total
        var e = ((end + offset) % 1f + 1f) % 1f * total
        if (abs(end - start) >= 0.9999f) { s = 0f; e = total }
        val out = Path()
        fun take(from: Float, to: Float) {
            val m = PathMeasure(path, false)
            var at = 0f
            var i = 0
            do {
                val len = lengths[i++]
                val a = max(from, at)
                val b = minOf(to, at + len)
                if (b > a) m.getSegment(a - at, b - at, out, true)
                at += len
            } while (m.nextContour() && i < lengths.size)
        }
        if (s <= e) take(s, e) else { take(s, total); take(0f, e) }
        return out
    }

    private fun withAlpha(color: Int, a: Float): Int = (Math.round(a.coerceIn(0f, 1f) * 255f) shl 24) or (color and 0xFFFFFF)
}
