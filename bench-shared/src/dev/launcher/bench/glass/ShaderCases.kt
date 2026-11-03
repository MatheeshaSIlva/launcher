package dev.launcher.bench.glass

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The launcher's own AGSL (tools/bench/extract_shaders.py) drawn with fixed uniforms over fixed pictures, on the phone
 * (RuntimeShader, RenderEffect) and on the desktop (Skiko, as Compose Multiplatform would): ENGINE_SPEC open question
 * "confirm RenderEffect and AGSL equivalents on desktop so the glass preview matches".
 */
object ShaderCases {
    const val W = 720
    const val H = 480

    /** A picture with hard edges, gradients and fine lines (what refraction and dispersion move), ARGB, opaque. */
    fun picture(seed: Int): IntArray {
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val u = x / W.toDouble()
            val v = y / H.toDouble()
            var r = 0.5 + 0.5 * sin(2 * PI * (u * (1.5 + seed) + v * 0.7))
            var g = 0.5 + 0.5 * sin(2 * PI * (v * (2.0 + seed * 0.5)) + 1.3)
            var b = 0.5 + 0.5 * cos(2 * PI * (u * 0.8 - v * 1.1 + seed * 0.25))
            if (((x / 48) + (y / 48)) % 2 == 0) { r *= 0.75; g *= 0.75; b *= 0.75 }
            if (x % 24 == 0 || y % 24 == 0) { r = 1.0; g = 1.0; b = 1.0 }
            val cx = x - W * (0.3 + 0.4 * seed)
            val cy = y - H * 0.5
            if (cx * cx + cy * cy < 90.0 * 90.0) { r = 0.95; g = 0.2 + 0.6 * seed; b = 0.1 }
            px[y * W + x] = (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
        return px
    }

    private fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()

    class Case(
        val name: String,
        val shader: String,
        val uniforms: Map<String, FloatArray>,
        /** Child shader name to picture seed. */
        val children: Map<String, Int>,
        /** As a RenderEffect over the picture of [children]'s first entry blurred by this radius (Android's radius), 0: a paint. */
        val effectBlurRadius: Float = 0f,
    )

    private fun f(vararg v: Float) = v

    private fun material(reveal: Boolean, extra: Map<String, FloatArray> = emptyMap()): Map<String, FloatArray> {
        val m = HashMap<String, FloatArray>()
        m["local0"] = f(80f, 70f)
        m["origin"] = f(80f, 70f)
        m["size"] = f(560f, 300f)
        m["clampSize"] = f(W.toFloat(), H.toFloat())
        m["placeScale"] = f(1f)
        m["depthC"] = f(W / 2f, H / 2f)
        m["depthK"] = f(1f)
        m["radii"] = f(64f, 64f, 64f, 64f)
        m["smoothing"] = f(0.6f)
        m["depth"] = f(44f)
        m["bend"] = f(36f)
        m["dispersion"] = f(0.35f)
        m["light"] = f(0.8f)
        m["lightDir"] = f(-0.6f, -0.8f)
        m["rimWidth"] = f(3f)
        m["alpha"] = f(1f)
        m["dropReach"] = f(48f)
        m["revealProgress"] = f(if (reveal) 0.45f else 1f)
        m["revealTime"] = f(1.3f)
        m["revealOrigin"] = f(W / 2f, H * 0.45f)
        m["revealMaxDist"] = f(520f)
        m["revealCell"] = f(8f)
        // Fills: a white veil (normal), a tint (multiply), a vertical gradient (screen).
        val fillColor = FloatArray(12 * 4)
        val fillMode = FloatArray(12)
        val fillType = FloatArray(12)
        val fillGeom = FloatArray(12 * 4)
        val fillStops = FloatArray(12)
        val stopColor = FloatArray(12 * 4 * 4)
        val stopPos = FloatArray(12 * 4)
        floatArrayOf(1f, 1f, 1f, 0.18f).copyInto(fillColor, 0)
        floatArrayOf(0.55f, 0.75f, 1f, 0.5f).copyInto(fillColor, 4); fillMode[1] = 1f
        floatArrayOf(1f, 1f, 1f, 1f).copyInto(fillColor, 8); fillMode[2] = 2f; fillType[2] = 1f
        floatArrayOf(0f, 0f, 0f, 1f).copyInto(fillGeom, 8); fillStops[2] = 2f
        floatArrayOf(1f, 1f, 1f, 0.25f, 0f, 0f, 0f, 0f).copyInto(stopColor, 2 * 16)
        floatArrayOf(0f, 1f).copyInto(stopPos, 2 * 4)
        m["fillColor"] = fillColor; m["fillMode"] = fillMode; m["fillType"] = fillType; m["fillGeom"] = fillGeom
        m["fillStops"] = fillStops; m["stopColor"] = stopColor; m["stopPos"] = stopPos
        m["strokeColor"] = FloatArray(2 * 4).also { floatArrayOf(1f, 1f, 1f, 0.5f).copyInto(it, 0) }
        m["strokeGeom"] = FloatArray(2 * 4).also { floatArrayOf(0f, 1.5f).copyInto(it, 0) }
        m["innerColor"] = FloatArray(4 * 4).also { floatArrayOf(0f, 0f, 0f, 0.3f).copyInto(it, 0) }
        m["innerGeom"] = FloatArray(4 * 4).also { floatArrayOf(0f, 4f, 10f, 0f).copyInto(it, 0) }
        m["dropColor"] = FloatArray(4 * 4).also { floatArrayOf(0f, 0f, 0f, 0.25f).copyInto(it, 0) }
        m["dropGeom"] = FloatArray(4 * 4).also { floatArrayOf(0f, 10f, 22f, 0f).copyInto(it, 0) }
        m.putAll(extra)
        return m
    }

    val ALL: List<Case> = listOf(
        Case("material-glass", "material", material(false), mapOf("backdrop" to 0, "backdropOld" to 1)),
        Case("material-reveal", "material", material(true), mapOf("backdrop" to 0, "backdropOld" to 1)),
        Case("material-press", "material", material(false, mapOf("press" to f(1f), "smoothing" to f(0f))),
            mapOf("backdrop" to 0, "backdropOld" to 1)),
        Case("material-effect", "material", material(false, mapOf("local0" to f(80f, 70f))),
            mapOf("backdrop" to 0, "backdropOld" to 1), effectBlurRadius = 30f),
        Case("edge-clamp", "edge-clamp", mapOf("lo" to f(100f, 80f), "hi" to f(600f, 380f)), mapOf("content" to 0)),
    )

    /** `uniform shader NAME;` declarations of [src]. */
    fun childNames(src: String): List<String> =
        Regex("""uniform\s+shader\s+(\w+)\s*;""").findAll(src).map { it.groupValues[1] }.toList()

    /** Every float-typed uniform of [src] with its total float count (arrays included). */
    fun floatUniforms(src: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        Regex("""uniform\s+(float|half)([234]?)(x[234])?\s+(\w+)\s*(\[(\d+)])?\s*;""").findAll(src).forEach { m ->
            val comps = (m.groupValues[2].toIntOrNull() ?: 1) * (m.groupValues[3].drop(1).toIntOrNull() ?: 1)
            out[m.groupValues[4]] = comps * (m.groupValues[6].toIntOrNull() ?: 1)
        }
        return out
    }

    /** [case]'s uniforms with every other float uniform of [src] at 0, each at its full size. */
    fun resolved(case: Case, src: String): Map<String, FloatArray> {
        val decl = floatUniforms(src)
        for (k in case.uniforms.keys) require(k in decl) { "${case.name}: no uniform $k in ${case.shader}" }
        return decl.mapValues { (k, n) ->
            val given = case.uniforms[k] ?: FloatArray(0)
            require(given.size <= n) { "${case.name}: $k takes $n floats, given ${given.size}" }
            given.copyOf(n)
        }
    }
}
