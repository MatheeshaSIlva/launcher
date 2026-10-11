package dev.launcher.app.design

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Material You's palettes made here (docs/PLAN_SETTINGS.md, C3): from the wallpaper's main colour, in one of Android's
 * styles (Tonal spot, Vibrant, Expressive, Neutral, Monochrome: the choices of the phone's Wallpaper & style), at an
 * intensity (how colourful the whole palette is: 0 grey, 1 the style's own, up to 2). "The phone's" style uses the system's
 * palette as it is (only its colourfulness scaled by the intensity).
 *
 * The palettes are tones of one hue and chroma each, as Material's tonal palettes, made in CIELAB's lightness (a tone
 * is L*, as in Material's HCT) with the chroma brought into sRGB where a tone cannot hold it; the styles' hues and
 * chromas follow Material's scheme definitions. Not Material's HCT colour space exactly: close, and no library.
 *
 * Names are those the themes use: `system_accent1_500` (a palette at a shade: Android's 0..1000 numbering) and
 * `system_<role>_light` / `_dark` (Android 14's roles: primary, surface_container_high...). Pure: unit-tested.
 */
object MaterialYou {
    enum class Style(val id: String, val title: String) {
        SYSTEM("system", "The phone's"), TONAL("tonal", "Tonal"), VIBRANT("vibrant", "Vibrant"),
        EXPRESSIVE("expressive", "Expressive"), NEUTRAL("neutral", "Neutral"), MONOCHROME("monochrome", "Monochrome");
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM }
    }

    /** One hue and chroma; [tone] is CIELAB L* (0 black .. 100 white). */
    class TonalPalette(val hue: Double, val chroma: Double) {
        private val cache = HashMap<Int, Int>()
        fun tone(t: Double): Int = cache.getOrPut((t * 10).toInt()) { lch(t, chroma, hue) }
    }

    class Scheme(
        val primary: TonalPalette, val secondary: TonalPalette, val tertiary: TonalPalette,
        val neutral: TonalPalette, val neutralVariant: TonalPalette, val error: TonalPalette,
    )

    /**
     * Everything that makes the palette: the [style], how colourful ([intensity]), the colour it is made from ([seed]; null:
     * the wallpaper's), and key colours the user set by hand ([keys]: 'P' primary, 'S' secondary, 'T' tertiary, 'N' neutral,
     * 'V' neutral variant; each palette then takes that colour's hue and chroma).
     */
    data class Choice(val style: Style, val intensity: Float, val seed: Int? = null, val keys: Map<Char, Int> = emptyMap()) {
        /** A key for caches of schemes made from it. */
        val id: String get() = "${style.id}/$intensity/${seed ?: "w"}/" + keys.entries.sortedBy { it.key }.joinToString(",") { "${it.key}${it.value}" }
    }

    /** The palettes a key colour can be set for, with their names in the settings. */
    val KEY_PALETTES = listOf('P' to "Primary", 'S' to "Secondary", 'T' to "Tertiary", 'N' to "Neutral", 'V' to "Neutral variant")

    /** The scheme of [style] from [seed] (ARGB), every chroma times [intensity]; a palette in [keys] made from that colour. */
    fun scheme(seed: Int, style: Style, intensity: Float, keys: Map<Char, Int> = emptyMap()): Scheme {
        val base = styled(seed, style, intensity)
        if (keys.isEmpty()) return base
        val k = intensity.coerceIn(0f, 2f).toDouble()
        fun own(c: Char, p: TonalPalette) = keys[c]?.let { val (_, ch, h) = toLch(it); TonalPalette(norm(h), ch * k) } ?: p
        return Scheme(own('P', base.primary), own('S', base.secondary), own('T', base.tertiary), own('N', base.neutral),
            own('V', base.neutralVariant), base.error)
    }

    /** The palette colour [name] is drawn from ('P', 'S', 'T', 'N', 'V', 'E'), or null if it is not one of Android's. */
    fun paletteOf(name: String): Char? {
        SHADE.matchEntire(name)?.let { m ->
            return when (m.groupValues[1]) { "accent1" -> 'P'; "accent2" -> 'S'; "accent3" -> 'T'; "neutral1" -> 'N'; "neutral2" -> 'V'; else -> null }
        }
        ROLE.matchEntire(name)?.let { m -> return ROLES[m.groupValues[1]]?.palette }
        return null
    }

    private fun styled(seed: Int, style: Style, intensity: Float): Scheme {
        val (_, c0, h) = toLch(seed)
        val k = intensity.coerceIn(0f, 2f).toDouble()
        fun p(hue: Double, chroma: Double) = TonalPalette(norm(hue), chroma * k)
        return when (style) {
            Style.SYSTEM, Style.TONAL -> Scheme(p(h, 36.0), p(h, 16.0), p(h + 60, 24.0), p(h, 6.0), p(h, 8.0), p(25.0, 84.0))
            Style.VIBRANT -> Scheme(p(h, maxOf(c0, 60.0)), p(h + rotation(h, VIBRANT_SECONDARY), 24.0), p(h + rotation(h, VIBRANT_TERTIARY), 32.0),
                p(h, 10.0), p(h, 12.0), p(25.0, 84.0))
            Style.EXPRESSIVE -> Scheme(p(h + 240, 40.0), p(h + rotation(h, EXPRESSIVE_SECONDARY), 24.0), p(h + rotation(h, EXPRESSIVE_TERTIARY), 32.0),
                p(h + 15, 8.0), p(h + 15, 12.0), p(25.0, 84.0))
            Style.NEUTRAL -> Scheme(p(h, 12.0), p(h, 8.0), p(h, 16.0), p(h, 2.0), p(h, 2.0), p(25.0, 84.0))
            Style.MONOCHROME -> Scheme(p(h, 0.0), p(h, 0.0), p(h, 0.0), p(h, 0.0), p(h, 0.0), p(25.0, 84.0 * minOf(1.0, k)))
        }
    }

    /** Colour [name] in [scheme], or null if it is not a palette or role name. */
    fun color(scheme: Scheme, name: String): Int? {
        SHADE.matchEntire(name)?.let { m ->
            val pal = when (m.groupValues[1]) {
                "accent1" -> scheme.primary; "accent2" -> scheme.secondary; "accent3" -> scheme.tertiary
                "neutral1" -> scheme.neutral; "neutral2" -> scheme.neutralVariant; else -> return null
            }
            return pal.tone(toneOfShade(m.groupValues[2].toInt()))
        }
        ROLE.matchEntire(name)?.let { m ->
            val dark = m.groupValues[2] == "dark"
            val r = ROLES[m.groupValues[1]] ?: return null
            val pal = when (r.palette) {
                'P' -> scheme.primary; 'S' -> scheme.secondary; 'T' -> scheme.tertiary
                'N' -> scheme.neutral; 'V' -> scheme.neutralVariant; else -> scheme.error
            }
            return pal.tone(if (dark) r.dark else r.light)
        }
        return null
    }

    /** [argb] with its chroma times [k] (its lightness and hue kept): "The phone's" palette at an intensity. */
    fun scaleChroma(argb: Int, k: Float): Int {
        if (abs(k - 1f) < 0.001f) return argb
        val (l, c, h) = toLch(argb)
        return (argb and 0xFF000000.toInt()) or (lch(l, c * k.coerceIn(0f, 2f), h) and 0xFFFFFF)
    }

    // ------------------------------------------------------------------ Android's names

    /** Android's shade numbers (0 white .. 1000 black) as tones. */
    fun toneOfShade(n: Int): Double = when (n) {
        0 -> 100.0; 10 -> 99.0; 50 -> 95.0; 1000 -> 0.0
        else -> 100.0 - n / 10.0
    }

    private val SHADE = Regex("""system_(accent1|accent2|accent3|neutral1|neutral2)_(\d+)""")
    private val ROLE = Regex("""system_([a-z_]+?)_(light|dark)""")

    private class Role(val palette: Char, val light: Double, val dark: Double)

    // Material 3's role tones (light, dark).
    private val ROLES = mapOf(
        "primary" to Role('P', 40.0, 80.0), "on_primary" to Role('P', 100.0, 20.0),
        "primary_container" to Role('P', 90.0, 30.0), "on_primary_container" to Role('P', 10.0, 90.0),
        "inverse_primary" to Role('P', 80.0, 40.0),
        "primary_fixed" to Role('P', 90.0, 90.0), "primary_fixed_dim" to Role('P', 80.0, 80.0),
        "on_primary_fixed" to Role('P', 10.0, 10.0), "on_primary_fixed_variant" to Role('P', 30.0, 30.0),
        "secondary" to Role('S', 40.0, 80.0), "on_secondary" to Role('S', 100.0, 20.0),
        "secondary_container" to Role('S', 90.0, 30.0), "on_secondary_container" to Role('S', 10.0, 90.0),
        "secondary_fixed" to Role('S', 90.0, 90.0), "secondary_fixed_dim" to Role('S', 80.0, 80.0),
        "tertiary" to Role('T', 40.0, 80.0), "on_tertiary" to Role('T', 100.0, 20.0),
        "tertiary_container" to Role('T', 90.0, 30.0), "on_tertiary_container" to Role('T', 10.0, 90.0),
        "tertiary_fixed" to Role('T', 90.0, 90.0), "tertiary_fixed_dim" to Role('T', 80.0, 80.0),
        "error" to Role('E', 40.0, 80.0), "on_error" to Role('E', 100.0, 20.0),
        "error_container" to Role('E', 90.0, 30.0), "on_error_container" to Role('E', 10.0, 90.0),
        "background" to Role('N', 98.0, 6.0), "on_background" to Role('N', 10.0, 90.0),
        "surface" to Role('N', 98.0, 6.0), "on_surface" to Role('N', 10.0, 90.0),
        "surface_dim" to Role('N', 87.0, 6.0), "surface_bright" to Role('N', 98.0, 24.0),
        "surface_container_lowest" to Role('N', 100.0, 4.0), "surface_container_low" to Role('N', 96.0, 10.0),
        "surface_container" to Role('N', 94.0, 12.0), "surface_container_high" to Role('N', 92.0, 17.0),
        "surface_container_highest" to Role('N', 90.0, 22.0),
        "inverse_surface" to Role('N', 20.0, 90.0), "inverse_on_surface" to Role('N', 95.0, 20.0),
        "surface_variant" to Role('V', 90.0, 30.0), "on_surface_variant" to Role('V', 30.0, 80.0),
        "outline" to Role('V', 50.0, 60.0), "outline_variant" to Role('V', 80.0, 30.0),
    )

    // Material's hue rotations for the secondary and tertiary palettes of Vibrant and Expressive, by the seed's hue.
    private val HUES = doubleArrayOf(0.0, 41.0, 61.0, 101.0, 131.0, 181.0, 251.0, 301.0, 360.0)
    private val VIBRANT_SECONDARY = doubleArrayOf(18.0, 15.0, 10.0, 12.0, 15.0, 18.0, 15.0, 12.0, 12.0)
    private val VIBRANT_TERTIARY = doubleArrayOf(35.0, 30.0, 20.0, 25.0, 30.0, 35.0, 30.0, 25.0, 25.0)
    private val EXPRESSIVE_SECONDARY = doubleArrayOf(45.0, 95.0, 45.0, 20.0, 45.0, 90.0, 45.0, 45.0, 45.0)
    private val EXPRESSIVE_TERTIARY = doubleArrayOf(120.0, 120.0, 20.0, 45.0, 20.0, 15.0, 20.0, 120.0, 120.0)

    private fun rotation(hue: Double, by: DoubleArray): Double {
        val h = norm(hue)
        for (i in 0 until HUES.size - 1) if (h >= HUES[i] && h < HUES[i + 1]) return by[i]
        return by[0]
    }

    private fun norm(h: Double) = ((h % 360.0) + 360.0) % 360.0

    // ------------------------------------------------------------------ CIELAB (D65) <-> sRGB

    /** L*, C*, h (degrees) of [argb]. */
    fun toLch(argb: Int): Triple<Double, Double, Double> {
        val r = lin(((argb shr 16) and 0xFF) / 255.0)
        val g = lin(((argb shr 8) and 0xFF) / 255.0)
        val b = lin((argb and 0xFF) / 255.0)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        val fx = f(x); val fy = f(y); val fz = f(z)
        val l = 116 * fy - 16
        val a = 500 * (fx - fy)
        val bb = 200 * (fy - fz)
        return Triple(l, hypot(a, bb), norm(Math.toDegrees(atan2(bb, a))))
    }

    /** The sRGB colour of [l], [c], [h], its chroma lowered until it is one (opaque ARGB). */
    fun lch(l: Double, c: Double, h: Double): Int {
        if (l <= 0.0) return 0xFF000000.toInt()
        if (l >= 100.0) return 0xFFFFFFFF.toInt()
        rgb(l, c, h)?.let { return it }
        var lo = 0.0; var hi = c
        repeat(18) { val mid = (lo + hi) / 2; if (rgb(l, mid, h) != null) lo = mid else hi = mid }
        return rgb(l, lo, h) ?: grey(l)
    }

    private fun rgb(l: Double, c: Double, h: Double): Int? {
        val hr = Math.toRadians(h)
        val a = c * cos(hr); val b = c * sin(hr)
        val fy = (l + 16) / 116; val fx = fy + a / 500; val fz = fy - b / 200
        val x = 0.95047 * fi(fx); val y = fi(fy); val z = 1.08883 * fi(fz)
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        val e = 1e-4
        if (r < -e || r > 1 + e || g < -e || g > 1 + e || bl < -e || bl > 1 + e) return null
        return (0xFF shl 24) or (enc(r) shl 16) or (enc(g) shl 8) or enc(bl)
    }

    private fun grey(l: Double): Int { val v = enc(fi((l + 16) / 116)); return (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    private fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun enc(v: Double): Int { val c = v.coerceIn(0.0, 1.0); val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055; return (s * 255 + 0.5).toInt().coerceIn(0, 255) }
    private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    private fun fi(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)
}
