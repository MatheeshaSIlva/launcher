package dev.launcher.app.design

import org.json.JSONArray
import org.json.JSONObject

/** A token's value as a theme file holds it (before aliases are followed). */
sealed class Value {
    /**
     * A colour, light and dark (ARGB). With a [lightSpec] or [darkSpec] that mode's colour is the system's palette
     * (`@primary`, `@system_accent2_900/40`: Material You, from the wallpaper), looked up when drawn; [light] and [dark]
     * are then only what shows without a palette.
     */
    data class Color(val light: Int, val dark: Int, val lightSpec: String? = null, val darkSpec: String? = null) : Value() {
        val dynamic get() = lightSpec != null || darkSpec != null
    }
    data class Number(val v: Float, val unit: NumUnit) : Value()
    data class SpringV(val spring: Spring) : Value()
    data class Choice(val option: String) : Value()
    data class Text(val style: TextStyle) : Value()
    data class Mat(val material: Material) : Value()
    /** Another token's value (Figma's variable alias): `{"ref": "sys.color.label.primary"}`. */
    data class Alias(val key: String) : Value()
}

/** A token in a theme: its value, where the value came from, and an optional note (shown in the token editor). */
data class Entry(val value: Value, val src: Provenance, val note: String? = null)

/**
 * A theme: a named set of tokens (`assets/themes/<name>.json`, or the user's own edits). Plain JSON, one object per token:
 *
 * ```
 * "sys.color.label.primary": {"ref": "ref.color.labels.primary", "src": "kit:VariableID:507:29167"}
 * "ref.color.accents.blue":  {"light": "#0088ff", "dark": "#0091ff", "src": "kit:VariableID:507:29166"}
 * "comp.nc.platter.corner":  {"pt": 24, "src": "kit:5914:23547"}
 * "sys.motion.panel.open":   {"spring": [0.3, 1.0], "src": "measured:pass 5, test30"}
 * "sys.layout.shade":        {"choice": "ios27"}
 * "sys.type.body":           {"text": {"family": "text", "weight": 400, "size": 17, "line": 22, "tracking": -0.43}}
 * "sys.material.glass.clear": {"material": {"frost": 6, "lens": {...},
 *                                           "fills": [["#101010", 1, "LINEAR_DODGE"], ["{ref.x}", 0.04, "LUMINOSITY"]],
 *                                           "innerShadows": [["#282828", 0, -40, 10, -40, "LINEAR_DODGE"]], "shadows": [...]}}
 * ```
 * Colours are `#rrggbb` or `#rrggbbaa` (alpha last, as design tools write them). Numbers carry their unit as the key:
 * `pt`, `fraction`, `percent`, `deg`, `ms`, `factor`.
 */
class Theme(
    val name: String,
    val author: String,
    val version: String,
    val entries: Map<String, Entry>,
    /** The theme this one is built on (its id: the file name without `.json`): only what differs is written here. */
    val extends: String? = null,
    /**
     * The theme in the wallpaper's colours (Material You): the tokens that change when the user turns them on
     * (`sys.color.source` = "wallpaper"), usually its accents as palette roles. Any theme can have them.
     */
    val materialYou: Map<String, Entry> = emptyMap(),
) {
    companion object {
        const val FORMAT = 1

        fun parse(json: String): Theme {
            val o = JSONObject(json)
            val format = o.optInt("format", FORMAT)
            require(format <= FORMAT) { "theme format $format is newer than this app's ($FORMAT)" }
            return Theme(o.optString("name", "Untitled"), o.optString("author", ""), o.optString("version", ""),
                entries(o.optJSONObject("tokens")), o.optString("extends").ifEmpty { null }, entries(o.optJSONObject("materialYou")))
        }

        private fun entries(tokens: JSONObject?): Map<String, Entry> {
            val out = LinkedHashMap<String, Entry>()
            if (tokens == null) return out
            for (key in tokens.keys()) {
                if (key.startsWith("_")) continue   // comments
                val t = tokens.getJSONObject(key)
                out[key] = Entry(parseValue(key, t), Provenance.parse(t.optString("src").ifEmpty { null }), t.optString("note").ifEmpty { null })
            }
            return out
        }

        /** A colour token's light or dark: a literal, or a palette reference kept as it is (resolved when drawn). */
        private fun colorPart(s: String): Pair<Int, String?> = if (s.startsWith("@")) FALLBACK_SYSTEM to s else color(s) to null

        private fun parseValue(key: String, t: JSONObject): Value = when {
            t.has("ref") -> Value.Alias(t.getString("ref"))
            t.has("light") || t.has("dark") -> {
                val l = colorPart(t.optString("light").ifEmpty { t.getString("dark") })
                val d = if (t.has("dark")) colorPart(t.getString("dark")) else l
                Value.Color(l.first, d.first, l.second, d.second)
            }
            t.has("color") -> colorPart(t.getString("color")).let { Value.Color(it.first, it.first, it.second, it.second) }
            t.has("pt") -> Value.Number(t.getDouble("pt").toFloat(), NumUnit.PT)
            t.has("fraction") -> Value.Number(t.getDouble("fraction").toFloat(), NumUnit.FRACTION)
            t.has("percent") -> Value.Number(t.getDouble("percent").toFloat(), NumUnit.PERCENT)
            t.has("deg") -> Value.Number(t.getDouble("deg").toFloat(), NumUnit.DEGREES)
            t.has("ms") -> Value.Number(t.getDouble("ms").toFloat(), NumUnit.MS)
            t.has("factor") -> Value.Number(t.getDouble("factor").toFloat(), NumUnit.FACTOR)
            t.has("spring") -> t.getJSONArray("spring").let { Value.SpringV(Spring(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())) }
            t.has("choice") -> Value.Choice(t.getString("choice"))
            t.has("text") -> t.getJSONObject("text").let {
                Value.Text(TextStyle(it.optString("family", "text"), it.getInt("weight"), it.getDouble("size").toFloat(),
                    it.getDouble("line").toFloat(), it.optDouble("tracking", 0.0).toFloat()))
            }
            t.has("material") -> Value.Mat(material(t.getJSONObject("material")))
            else -> throw IllegalArgumentException("token $key: no value")
        }

        private fun material(m: JSONObject): Material = Material(
            frostPt = m.optDouble("frost", 0.0).toFloat(),
            frostDarkPt = m.optDouble("frostDark", m.optDouble("frost", 0.0)).toFloat(),
            lens = m.optJSONObject("lens")?.let {
                Lens(it.optDouble("refraction", 0.0).toFloat(), it.optDouble("depth", 0.0).toFloat(), it.optDouble("dispersion", 0.0).toFloat(),
                    it.optDouble("splay", 0.0).toFloat(), it.optDouble("light", 0.0).toFloat(), it.optDouble("lightAngle", 0.0).toFloat())
            },
            fills = list(m.optJSONArray("fills")) { a ->
                // Opacity: one number, or [light, dark].
                val op = a.optJSONArray(1)
                val l = op?.getDouble(0)?.toFloat() ?: a.getDouble(1).toFloat()
                val d = op?.getDouble(1)?.toFloat() ?: l
                Fill(colorValue(a.getString(0)), l, d, Blend.valueOf(a.getString(2)))
            },
            innerShadows = list(m.optJSONArray("innerShadows")) { shadow(it) },
            shadows = list(m.optJSONArray("shadows")) { shadow(it) },
        )

        private fun shadow(a: JSONArray) = Shadow(colorValue(a.getString(0)), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(),
            a.getDouble(3).toFloat(), a.getDouble(4).toFloat(), Blend.valueOf(a.getString(5)))

        private fun <T> list(a: JSONArray?, f: (JSONArray) -> T): List<T> = if (a == null) emptyList() else List(a.length()) { f(a.getJSONArray(it)) }

        /**
         * `#rrggbb` / `#rrggbbaa` (alpha last), `#light|#dark`, `{key}` (a colour token), or the system's palette: `@role`
         * (`@primary`), `@system_accent1_200`, with an alpha in percent (`@primary/40`), alone or as one side of `light|dark`.
         */
        fun colorValue(s: String): ColorValue = when {
            s.startsWith("{") && s.endsWith("}") -> ColorValue.Ref(s.substring(1, s.length - 1))
            '@' in s -> if ('|' in s) s.split('|').let { ColorValue.Dynamic(it[0], it[1]) } else ColorValue.Dynamic(s, s)
            '|' in s -> s.split('|').let { ColorValue.Literal(color(it[0]), color(it[1])) }
            else -> color(s).let { ColorValue.Literal(it, it) }
        }

        /** What a palette colour is when there is none to read (the unit tests, Android before 12). */
        const val FALLBACK_SYSTEM = 0xFF808080.toInt()

        /**
         * A colour [spec] in the [dark] or light appearance: `#rrggbb[aa]`, or a palette reference: `@name` is the role's
         * light or dark colour (`system_<name>_light` / `_dark`, Android 14's Material roles: `@primary`,
         * `@surface_container_high`, `@on_surface_variant`), `@system_...` one exact palette colour (`@system_accent1_200`,
         * both appearances); `/40` sets the alpha in percent. [palette] looks a name up (null: unknown, [FALLBACK_SYSTEM]).
         */
        fun resolveSpec(spec: String, dark: Boolean, palette: (String) -> Int?): Int {
            if (!spec.startsWith("@")) return color(spec)
            val body = spec.removePrefix("@")
            val name = body.substringBefore('/')
            val alpha = body.substringAfter('/', "").toIntOrNull()?.coerceIn(0, 100)
            val c = (if (name.startsWith("system_")) palette(name) else palette("system_${name}_" + if (dark) "dark" else "light")) ?: FALLBACK_SYSTEM
            return if (alpha == null) c else (Math.round(alpha * 255f / 100f) shl 24) or (c and 0xFFFFFF)
        }

        fun color(s: String): Int {
            require(s.startsWith("#") && (s.length == 7 || s.length == 9)) { "colour '$s': expected #rrggbb or #rrggbbaa" }
            val rgb = s.substring(1, 7).toLong(16).toInt()
            val a = if (s.length == 9) s.substring(7, 9).toInt(16) else 0xFF
            return (a shl 24) or rgb
        }

        fun hex(argb: Int): String {
            val a = (argb ushr 24) and 0xFF
            val rgb = String.format("%06x", argb and 0xFFFFFF)
            return if (a == 0xFF) "#$rgb" else "#$rgb" + String.format("%02x", a)
        }

        /** One token as the theme file writes it (the user's edits are saved this way). */
        fun write(e: Entry): JSONObject {
            val o = JSONObject()
            when (val v = e.value) {
                is Value.Alias -> o.put("ref", v.key)
                is Value.Color -> {
                    val l = v.lightSpec ?: hex(v.light)
                    val d = v.darkSpec ?: hex(v.dark)
                    if (l == d) o.put("color", l) else o.put("light", l).put("dark", d)
                }
                is Value.Number -> o.put(when (v.unit) {
                    NumUnit.PT -> "pt"; NumUnit.FRACTION -> "fraction"; NumUnit.PERCENT -> "percent"
                    NumUnit.DEGREES -> "deg"; NumUnit.MS -> "ms"; NumUnit.FACTOR -> "factor"
                }, v.v.toDouble())
                is Value.SpringV -> o.put("spring", JSONArray().put(v.spring.response.toDouble()).put(v.spring.damping.toDouble()))
                is Value.Choice -> o.put("choice", v.option)
                is Value.Text -> o.put("text", JSONObject().put("family", v.style.family).put("weight", v.style.weight)
                    .put("size", v.style.sizePt.toDouble()).put("line", v.style.lineHeightPt.toDouble()).put("tracking", v.style.trackingPt.toDouble()))
                is Value.Mat -> o.put("material", writeMaterial(v.material))
            }
            o.put("src", e.src.encode())
            e.note?.let { o.put("note", it) }
            return o
        }

        private fun colorString(c: ColorValue) = when (c) {
            is ColorValue.Ref -> "{${c.key}}"
            is ColorValue.Literal -> if (c.light == c.dark) hex(c.light) else hex(c.light) + "|" + hex(c.dark)
            is ColorValue.Dynamic -> if (c.light == c.dark) c.light else c.light + "|" + c.dark
        }

        private fun writeMaterial(m: Material): JSONObject {
            val o = JSONObject().put("frost", m.frostPt.toDouble())
            if (m.frostDarkPt != m.frostPt) o.put("frostDark", m.frostDarkPt.toDouble())
            m.lens?.let { l ->
                o.put("lens", JSONObject().put("refraction", l.refraction.toDouble()).put("depth", l.depthPt.toDouble()).put("dispersion", l.dispersion.toDouble())
                    .put("splay", l.splay.toDouble()).put("light", l.light.toDouble()).put("lightAngle", l.lightAngle.toDouble()))
            }
            o.put("fills", JSONArray().apply {
                m.fills.forEach {
                    val op: Any = if (it.opacity == it.opacityDark) it.opacity.toDouble() else JSONArray().put(it.opacity.toDouble()).put(it.opacityDark.toDouble())
                    put(JSONArray().put(colorString(it.color)).put(op).put(it.blend.name))
                }
            })
            fun shadows(list: List<Shadow>) = JSONArray().apply {
                list.forEach { put(JSONArray().put(colorString(it.color)).put(it.dx.toDouble()).put(it.dy.toDouble()).put(it.blur.toDouble()).put(it.spread.toDouble()).put(it.blend.name)) }
            }
            o.put("innerShadows", shadows(m.innerShadows)).put("shadows", shadows(m.shadows))
            return o
        }

        /** A theme file holding [entries] (the user's edits: `files/design/user.json`). */
        fun write(name: String, entries: Map<String, Entry>): String {
            val tokens = JSONObject()
            for ((k, e) in entries) tokens.put(k, write(e))
            return JSONObject().put("format", FORMAT).put("name", name).put("tokens", tokens).toString(2)
        }
    }
}

/**
 * Tokens resolved: a base theme with layers over it (the user's edits on top), aliases followed; palette colours looked up
 * through [palette] (an `android.R.color` name to its colour; the unit tests give their own). Pure (no Android), so the
 * rules are unit-tested; [dev.launcher.app.design.Design] is the app's live instance.
 */
class Resolver(private val layers: List<Map<String, Entry>>, private val palette: (String) -> Int? = { null }) {
    /** The entry for [key] as the top-most layer that has it gives it (null: no layer has it). */
    fun entry(key: String): Entry? {
        for (i in layers.indices.reversed()) layers[i][key]?.let { return it }
        return null
    }

    /** [key]'s value with aliases followed (a cycle or a dangling alias is an error, reported with the chain). */
    fun resolve(key: String): Value {
        var k = key
        val seen = ArrayList<String>()
        while (true) {
            if (k in seen) throw IllegalStateException("token alias cycle: ${(seen + k).joinToString(" -> ")}")
            seen += k
            val v = entry(k)?.value ?: throw IllegalStateException("token '$k' is missing" + if (seen.size > 1) " (via ${seen.joinToString(" -> ")})" else "")
            if (v is Value.Alias) { k = v.key; continue }
            return v
        }
    }

    /** The colour of [c] (a literal, a colour token or palette colours) as light and dark ARGB. */
    fun colorPair(c: ColorValue): Pair<Int, Int> = when (c) {
        is ColorValue.Literal -> c.light to c.dark
        is ColorValue.Ref -> pair(resolve(c.key) as? Value.Color ?: throw IllegalStateException("token '${c.key}' is not a colour"))
        is ColorValue.Dynamic -> Theme.resolveSpec(c.light, false, palette) to Theme.resolveSpec(c.dark, true, palette)
    }

    /** A colour value's light and dark ARGB (its palette references looked up). */
    fun pair(v: Value.Color): Pair<Int, Int> =
        (v.lightSpec?.let { Theme.resolveSpec(it, false, palette) } ?: v.light) to (v.darkSpec?.let { Theme.resolveSpec(it, true, palette) } ?: v.dark)

    /** Every key any layer defines. */
    fun keys(): Set<String> = layers.flatMapTo(LinkedHashSet()) { it.keys }
}

/** How a theme's layers stack. Pure (no Android): unit-tested. */
object ThemeLayers {
    /**
     * The layers a theme resolves from: the themes it is built on (base first), their Material You sections when
     * [materialYou] (all of them, the later winning), then the user's edits.
     */
    fun of(chain: List<Theme>, edits: Map<String, Entry>, materialYou: Boolean): List<Map<String, Entry>> {
        val my = LinkedHashMap<String, Entry>()
        if (materialYou) for (t in chain) my.putAll(t.materialYou)
        return chain.map { it.entries } + (if (my.isEmpty()) emptyList() else listOf(my)) + listOf(LinkedHashMap(edits))
    }

    /** The user (or the theme) chose the wallpaper's colours (`sys.color.source` = "wallpaper"). */
    fun fromWallpaper(chain: List<Theme>, edits: Map<String, Entry>): Boolean =
        (try { Resolver(of(chain, edits, false)).resolve("sys.color.source") } catch (_: Throwable) { null } as? Value.Choice)?.option == "wallpaper"
}

/** Checks a theme against the theme the code is written for. Pure (no Android): unit-tested. */
object ThemeCheck {
    /**
     * Null if [layers] (a theme, the ones it is built on and edits; the base first) resolve and give every token of [base]
     * in the same kind (a missing or mistyped token would fail where it is drawn); else what is wrong.
     */
    fun against(base: Map<String, Entry>, layers: List<Map<String, Entry>>): String? {
        val want = Resolver(listOf(base))
        val got = Resolver(layers)
        for (k in got.keys()) try { got.resolve(k) } catch (t: Throwable) { return t.message }
        for (k in base.keys) {
            val a = try { want.resolve(k) } catch (_: Throwable) { continue }
            val b = try { got.resolve(k) } catch (t: Throwable) { return "token '$k' is missing (${t.message})" }
            if (a::class != b::class) return "token '$k' is a ${b::class.simpleName}, the code reads a ${a::class.simpleName}"
        }
        return null
    }
}
