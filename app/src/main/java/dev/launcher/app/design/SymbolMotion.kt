package dev.launcher.app.design

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One symbol's animation (the `play` block of an icon set, format 2: the shape is described at the top of
 * `tools/icons/build_icon_set.py`, whose `evaluate` this must equal), parsed once into flat arrays. [evaluate] fills a
 * [Pose] made once by [newPose]; it allocates nothing, so a frame of it costs the garbage collector nothing. No `android.*`
 * types: it runs in JVM tests (`SymbolMotionTest` compares it with the builder's own values, 1e-3).
 *
 * A target's value at t: its base, then every track that has begun, in file order, at its eased fraction (held at its end
 * after it; overshoot is kept, except an alpha is clamped to 0..1 and a stroke width to 0 and up, as drawn). Groups compose
 * parent first as Android's VectorDrawable does (pivot, scale, rotate, translate), a motion path is a polyline walked by
 * cumulative length and extended straight past either end.
 */
class SymbolMotion(play: JSONObject) {
    /** The whole length, ms. */
    val ms = play.getDouble("ms").toFloat()

    private val groupCount: Int
    private val gParent: IntArray
    private val gBase: FloatArray   // per group: pivot x, y, scale x, y, rotate, translate x, y

    /** Per layer, in drawing order: its group (-1: none), its path data, the clips it sits under (indexes into the clips). */
    val layerGroup: IntArray
    val layerD: Array<String>
    val layerClips: Array<IntArray>

    /** Per layer, as the builder wrote them (read once, for the drawer): stroke cap (0 butt, 1 round, 2 square), join (0 miter, 1 round, 2 bevel), even-odd fill. */
    val layerCap: IntArray
    val layerJoin: IntArray
    val layerEvenOdd: BooleanArray

    /** Per layer that morphs, else null: its path's commands (0 M, 1 L, 2 C, 3 Z) and the numbers it starts from. */
    val layerCmds: Array<IntArray?>
    private val layerBase: Array<FloatArray?>
    private val lBase: FloatArray   // per layer: fill alpha, stroke alpha, stroke width, trim start, end, offset

    val clipGroup: IntArray
    val clipD: Array<String>
    val clipCmds: Array<IntArray?>
    private val clipBase: Array<FloatArray?>

    private val tracks: Int
    private val tKind: IntArray      // 0 group, 1 layer, 2 clip
    private val tIdx: IntArray
    private val tProp: IntArray      // PROPS order
    private val tAt: FloatArray
    private val tDur: FloatArray
    private val tEase: FloatArray    // 4 a track: a cubic's controls, or NaN: accelerate-decelerate
    private val tFrom: FloatArray
    private val tTo: FloatArray
    private val tA: Array<FloatArray?>   // a path's `from`; a motion path's points
    private val tB: Array<FloatArray?>   // a path's `to`; a motion path's cumulative lengths

    val layerCount: Int get() = layerGroup.size
    val clipCount: Int get() = clipGroup.size

    init {
        val gs = play.getJSONArray("groups")
        groupCount = gs.length()
        gParent = IntArray(groupCount)
        gBase = FloatArray(groupCount * 7)
        for (i in 0 until groupCount) {
            val g = gs.getJSONObject(i)
            gParent[i] = g.getInt("parent")
            require(gParent[i] < i) { "group $i comes before its parent" }
            val v = floatArrayOf(*pair(g, "pivot", 0f), *pair(g, "scale", 1f), g.optDouble("rotate", 0.0).toFloat(), *pair(g, "translate", 0f))
            v.copyInto(gBase, i * 7)
        }
        val ls = play.getJSONArray("layers")
        val cs = play.optJSONArray("clips") ?: JSONArray()
        layerGroup = IntArray(ls.length()) { ls.getJSONObject(it).getInt("g") }
        layerD = Array(ls.length()) { ls.getJSONObject(it).getString("d") }
        layerClips = Array(ls.length()) { ls.getJSONObject(it).optJSONArray("clip")?.let { c -> IntArray(c.length()) { j -> c.getInt(j) } } ?: NONE }
        layerCap = IntArray(ls.length()) { enumOf(ls.getJSONObject(it), "cap", CAPS) }
        layerJoin = IntArray(ls.length()) { enumOf(ls.getJSONObject(it), "join", JOINS) }
        layerEvenOdd = BooleanArray(ls.length()) { ls.getJSONObject(it).optInt("even", 0) == 1 }
        layerCmds = arrayOfNulls(ls.length())
        layerBase = arrayOfNulls(ls.length())
        lBase = FloatArray(ls.length() * 6)
        for (i in 0 until ls.length()) {
            val l = ls.getJSONObject(i)
            val tm = l.optJSONArray("trim")
            floatArrayOf(l.optDouble("a", 1.0).toFloat(), l.optDouble("sa", 1.0).toFloat(), l.optDouble("s", 0.0).toFloat(),
                tm?.getDouble(0)?.toFloat() ?: 0f, tm?.getDouble(1)?.toFloat() ?: 1f, tm?.getDouble(2)?.toFloat() ?: 0f).copyInto(lBase, i * 6)
            require(layerGroup[i] < groupCount && layerClips[i].all { it in 0 until cs.length() }) { "layer $i points outside" }
        }
        clipGroup = IntArray(cs.length()) { cs.getJSONObject(it).getInt("g") }
        clipD = Array(cs.length()) { cs.getJSONObject(it).getString("d") }
        clipCmds = arrayOfNulls(cs.length())
        clipBase = arrayOfNulls(cs.length())

        val ts = play.getJSONArray("tracks")
        tracks = ts.length()
        tKind = IntArray(tracks); tIdx = IntArray(tracks); tProp = IntArray(tracks); tAt = FloatArray(tracks); tDur = FloatArray(tracks)
        tEase = FloatArray(tracks * 4); tFrom = FloatArray(tracks); tTo = FloatArray(tracks)
        tA = arrayOfNulls(tracks); tB = arrayOfNulls(tracks)
        for (k in 0 until tracks) {
            val t = ts.getJSONObject(k)
            tKind[k] = "glc".indexOf(t.getString("k")).also { require(it >= 0) { "track $k: target kind" } }
            tIdx[k] = t.getInt("i")
            tProp[k] = PROPS.indexOf(t.getString("p")).also { require(it >= 0) { "track $k: property ${t.getString("p")}" } }
            val p = tProp[k]
            require(when (tKind[k]) { 0 -> p <= TRANSLATE_XY; 1 -> p >= PATH; else -> p == PATH }) { "track $k: ${PROPS[p]} on target kind ${tKind[k]}" }
            require(tIdx[k] in 0 until when (tKind[k]) { 0 -> groupCount; 1 -> layerCount; else -> clipCount }) { "track $k: target ${tIdx[k]}" }
            tAt[k] = t.getDouble("at").toFloat(); tDur[k] = t.getDouble("ms").toFloat()
            val e = t.get("e")
            for (j in 0 until 4) tEase[k * 4 + j] = if (e is JSONArray) e.getDouble(j).toFloat() else Float.NaN
            when (p) {
                TRANSLATE_XY -> { tA[k] = floats(t.getJSONArray("pts")); tB[k] = floats(t.getJSONArray("len")) }
                PATH -> {
                    tA[k] = floats(t.getJSONArray("from")); tB[k] = floats(t.getJSONArray("to"))
                    val c = tKind[k] == 2
                    val (cmds, nums) = parsePath(if (c) clipD[tIdx[k]] else layerD[tIdx[k]])
                    require(nums.size == tA[k]!!.size && nums.size == tB[k]!!.size) { "track $k: path numbers differ from its layer's" }
                    if (c) { clipCmds[tIdx[k]] = cmds; clipBase[tIdx[k]] = nums } else { layerCmds[tIdx[k]] = cmds; layerBase[tIdx[k]] = nums }
                }
                else -> { tFrom[k] = t.getDouble("from").toFloat(); tTo[k] = t.getDouble("to").toFloat() }
            }
        }
    }

    /** What [evaluate] writes. Per layer: `matrix` (6: x' = a x + b y + c, y' = d x + e y + f, Android's order), `fill`,
     *  `stroke` (alpha), `width`, `trim` (start, end, offset) and `path` (the morphed numbers of its [layerCmds], null: draw its
     *  own data); per clip `clipMatrix` and `clipPath`. Made by [newPose] (one per drawer), read until the next [evaluate]. */
    class Pose internal constructor(m: SymbolMotion) {
        val matrix = FloatArray(m.layerCount * 6)
        val fill = FloatArray(m.layerCount)
        val stroke = FloatArray(m.layerCount)
        val width = FloatArray(m.layerCount)
        val trim = FloatArray(m.layerCount * 3)
        val path: Array<FloatArray?> = Array(m.layerCount) { m.layerBase[it]?.let { b -> FloatArray(b.size) } }
        val clipMatrix = FloatArray(m.clipCount * 6)
        val clipPath: Array<FloatArray?> = Array(m.clipCount) { m.clipBase[it]?.let { b -> FloatArray(b.size) } }
        internal val prop = FloatArray(m.groupCount * 5)    // per group: rotation, scale x, y, translate x, y
        internal val world = FloatArray(m.groupCount * 6)
    }

    fun newPose() = Pose(this)

    /** The symbol at [tMs] into its animation (held at its end past [ms]; before 0 at its start), written into [out]. */
    fun evaluate(tMs: Float, out: Pose) {
        val p = out.prop
        for (g in 0 until groupCount) {
            val b = g * 7
            p[g * 5] = gBase[b + 4]; p[g * 5 + 1] = gBase[b + 2]; p[g * 5 + 2] = gBase[b + 3]; p[g * 5 + 3] = gBase[b + 5]; p[g * 5 + 4] = gBase[b + 6]
        }
        for (i in 0 until layerCount) {
            out.fill[i] = lBase[i * 6]; out.stroke[i] = lBase[i * 6 + 1]; out.width[i] = lBase[i * 6 + 2]
            out.trim[i * 3] = lBase[i * 6 + 3]; out.trim[i * 3 + 1] = lBase[i * 6 + 4]; out.trim[i * 3 + 2] = lBase[i * 6 + 5]
            layerBase[i]?.copyInto(out.path[i]!!)
        }
        for (i in 0 until clipCount) clipBase[i]?.copyInto(out.clipPath[i]!!)
        for (k in 0 until tracks) {
            if (tMs < tAt[k]) continue
            val y = ease(k, if (tDur[k] <= 0f) 1f else min(1f, (tMs - tAt[k]) / tDur[k]))
            val i = tIdx[k]
            val prop = tProp[k]
            when {
                prop == TRANSLATE_XY -> along(tA[k]!!, tB[k]!!, y, p, i * 5 + 3)
                prop == PATH -> {
                    val to = if (tKind[k] == 2) out.clipPath[i]!! else out.path[i]!!
                    val a = tA[k]!!; val b = tB[k]!!
                    for (j in to.indices) to[j] = a[j] + (b[j] - a[j]) * y
                }
                else -> {
                    val v = tFrom[k] + (tTo[k] - tFrom[k]) * y
                    when (prop) {
                        in 0..4 -> p[i * 5 + prop] = v
                        FILL -> out.fill[i] = v
                        STROKE -> out.stroke[i] = v
                        WIDTH -> out.width[i] = v
                        else -> out.trim[i * 3 + prop - TRIM] = v
                    }
                }
            }
        }
        for (i in 0 until layerCount) {   // clamped as drawn
            out.fill[i] = min(1f, max(0f, out.fill[i])); out.stroke[i] = min(1f, max(0f, out.stroke[i])); out.width[i] = max(0f, out.width[i])
        }
        for (g in 0 until groupCount) compose(g, p, out.world)
        for (i in 0 until layerCount) matrixOf(layerGroup[i], out.world, out.matrix, i * 6)
        for (i in 0 until clipCount) matrixOf(clipGroup[i], out.world, out.clipMatrix, i * 6)
    }

    /** Group [g]'s world matrix: its own (translate(t + pivot) rotate scale translate(-pivot)) after its parent's. */
    private fun compose(g: Int, p: FloatArray, w: FloatArray) {
        val b = g * 7; val q = g * 5
        val px = gBase[b]; val py = gBase[b + 1]
        val r = p[q] * (PI / 180.0).toFloat(); val c = cos(r); val s = sin(r)
        val a = c * p[q + 1]; val bb = -s * p[q + 2]; val d = s * p[q + 1]; val e = c * p[q + 2]
        val cc = p[q + 3] + px - (a * px + bb * py); val f = p[q + 4] + py - (d * px + e * py)
        val o = g * 6
        val par = gParent[g]
        if (par < 0) { w[o] = a; w[o + 1] = bb; w[o + 2] = cc; w[o + 3] = d; w[o + 4] = e; w[o + 5] = f; return }
        val m = par * 6
        w[o] = w[m] * a + w[m + 1] * d; w[o + 1] = w[m] * bb + w[m + 1] * e; w[o + 2] = w[m] * cc + w[m + 1] * f + w[m + 2]
        w[o + 3] = w[m + 3] * a + w[m + 4] * d; w[o + 4] = w[m + 3] * bb + w[m + 4] * e; w[o + 5] = w[m + 3] * cc + w[m + 4] * f + w[m + 5]
    }

    private fun matrixOf(g: Int, w: FloatArray, out: FloatArray, at: Int) {
        for (j in 0 until 6) out[at + j] = if (g >= 0) w[g * 6 + j] else if (j == 0 || j == 4) 1f else 0f
    }

    /** Track [k]'s eased fraction for [f] in 0..1 (a cubic Bezier solved for x, or accelerate-decelerate). */
    private fun ease(k: Int, f: Float): Float {
        val x1 = tEase[k * 4]
        if (x1.isNaN()) return 0.5f - 0.5f * cos(PI.toFloat() * f)
        if (f <= 0f) return 0f
        if (f >= 1f) return 1f
        val y1 = tEase[k * 4 + 1]; val x2 = tEase[k * 4 + 2]; val y2 = tEase[k * 4 + 3]
        var lo = 0f; var hi = 1f; var u = f   // Newton from f, kept inside a bracket that bisection narrows
        for (n in 0 until 16) {
            val v = 1f - u
            val x = 3 * v * v * u * x1 + 3 * v * u * u * x2 + u * u * u - f
            if (abs(x) < 1e-6f) break
            if (x < 0) lo = u else hi = u
            val dx = 3 * v * v * x1 + 6 * v * u * (x2 - x1) + 3 * u * u * (1 - x2)
            val next = if (abs(dx) > 1e-6f) u - x / dx else -1f
            u = if (next > lo && next < hi) next else (lo + hi) / 2
        }
        val v = 1f - u
        return 3 * v * v * u * y1 + 3 * v * u * u * y2 + u * u * u
    }

    private companion object {
        val NONE = IntArray(0)
        val PROPS = listOf("rotation", "scaleX", "scaleY", "translateX", "translateY", "translateXY", "pathData", "fillAlpha",
            "strokeAlpha", "strokeWidth", "trimPathStart", "trimPathEnd", "trimPathOffset")
        const val TRANSLATE_XY = 5; const val PATH = 6; const val FILL = 7; const val STROKE = 8; const val WIDTH = 9; const val TRIM = 10
        val ARGS = intArrayOf(2, 2, 6, 0)
        val CAPS = listOf("butt", "round", "square")
        val JOINS = listOf("miter", "round", "bevel")

        /** Layer [o]'s [key] as the index of its name in [names] (the first, the default, when it has none); an unknown name is a bad file. */
        fun enumOf(o: JSONObject, key: String, names: List<String>): Int =
            names.indexOf(o.optString(key, names[0])).also { require(it >= 0) { "layer ${o.optString("n")}: $key '${o.optString(key)}' is none of $names" } }

        fun pair(o: JSONObject, key: String, dflt: Float) = o.optJSONArray(key)?.let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } ?: floatArrayOf(dflt, dflt)
        fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

        /** A path the builder wrote for a morph (absolute M, L, C, Z): its commands and numbers. */
        fun parsePath(d: String): Pair<IntArray, FloatArray> {
            val cmds = ArrayList<Int>(); val nums = ArrayList<Float>()
            var i = 0
            while (i < d.length) {
                val c = "MLCZ".indexOf(d[i])
                if (c >= 0) { cmds += c; i++ } else if (d[i] == ' ' || d[i] == ',') i++ else {
                    var j = i + 1
                    while (j < d.length && d[j] != ' ' && d[j] != ',' && "MLCZ".indexOf(d[j]) < 0) j++
                    nums += d.substring(i, j).toFloat(); i = j
                }
            }
            require(nums.size == cmds.sumOf { ARGS[it] }) { "a morphing path is absolute M, L, C, Z: ${d.take(40)}" }
            return cmds.toIntArray() to nums.toFloatArray()
        }

        /** The point at fraction [y] of a polyline's length (points flat, [lens] cumulative) into [p] at [at], x then y. */
        fun along(pts: FloatArray, lens: FloatArray, y: Float, p: FloatArray, at: Int) {
            val n = lens.size
            if (n < 2 || lens[n - 1] == 0f) { p[at] = pts[0]; p[at + 1] = pts[1]; return }
            val want = y * lens[n - 1]
            var j = 1
            while (j < n - 1 && lens[j] < want) j++
            val d = lens[j] - lens[j - 1]
            val u = (want - lens[j - 1]) / (if (d == 0f) 1f else d)
            p[at] = pts[2 * j - 2] + (pts[2 * j] - pts[2 * j - 2]) * u
            p[at + 1] = pts[2 * j - 1] + (pts[2 * j + 1] - pts[2 * j - 1]) * u
        }
    }
}
