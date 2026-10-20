package dev.launcher.app.layout

import org.json.JSONArray
import org.json.JSONObject

/**
 * A layout file (docs/LAYOUT_FILES.md): one layout of one element described as data, as a theme file describes a look.
 * It says what the layout is (its id, name, origin and description, the places it can live and its options: what the
 * settings app shows) and, for an element whose structure is data (the shade so far: [ShadeStructure]), that structure.
 *
 * Read strictly: an unknown key or value, a missing one or one of another kind refuses the whole file with the reason
 * ([LayoutFileError]), so a typo never passes as a default.
 */
class LayoutFile(val spec: LayoutSpec, val shade: ShadeStructure?) {
    companion object {
        /** The format this build reads (a file of another is refused: it may mean something this build cannot do). */
        const val FORMAT = 1

        /** [json] as a layout file, or [LayoutFileError] naming what is wrong. */
        fun parse(json: String): LayoutFile {
            val root = try { JSONObject(json) } catch (e: org.json.JSONException) { throw LayoutFileError("not JSON: ${e.message}") }
            val o = Strict(root, "")
            val format = o.int("format")
            if (format != FORMAT) o.fail("format: $format (this build reads $FORMAT)")
            val element = Element.of(o.str("element")) ?: o.fail("element: no element '${o.str("element")}'")
            val id = o.str("id")
            if (!ID.matches(id)) o.fail("id: '$id' (lower case letters, digits and dashes)")
            val title = o.str("title")
            val origin = o.str("origin")
            val description = o.str("description")
            o.optStr("src")   // where it came from: for people, read by nothing
            val preview = o.optBool("preview") ?: false
            val placementObjs = o.objs("placements")
            val placements = placementObjs.map { p -> Placement(p.str("id"), p.str("title"), p.str("description")) }
            if (placements.isEmpty()) o.fail("placements: none (the first is the default)")
            if (placements.map { it.id }.toSet().size != placements.size) o.fail("placements: an id repeats")
            val options = if (o.has("options")) o.objs("options").map { option(it) } else emptyList()
            if (options.map { it.key }.toSet().size != options.size) o.fail("options: a key repeats")
            val spec = LayoutSpec(element, id, title, origin, description, placements, options, preview)
            val shade = when (element) {
                // (Each place says where each panel is pulled from: its `pulls`, read with the structure.)
                Element.SHADE -> ShadeStructure.parse(o.obj("structure"), options, placements.map { it.id }.zip(placementObjs))
                else -> if (o.has("structure")) o.fail("structure: ${element.id} has no structure as data yet") else null
            }
            // (Ended after the structure, which reads what is its in them.)
            placementObjs.forEach { it.end() }
            o.end()
            return LayoutFile(spec, shade)
        }

        private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        private fun option(o: Strict): Option {
            val key = o.str("key")
            val title = o.str("title")
            val description = o.str("description")
            val out = when (val kind = o.str("kind")) {
                "count" -> {
                    val min = o.int("min")
                    val max = o.int("max")
                    val default = o.int("default")
                    if (min > max || default !in min..max) o.fail("default $default, min $min, max $max")
                    Option.Count(key, title, description, default, min, max)
                }
                "toggle" -> Option.Toggle(key, title, description, o.bool("default"))
                "choice" -> {
                    val choices = o.objs("choices").map { c -> (c.str("id") to c.str("title")).also { c.end() } }
                    val default = o.str("default")
                    if (choices.none { it.first == default }) o.fail("default: '$default' is none of the choices")
                    Option.Choice(key, title, description, default, choices)
                }
                else -> o.fail("kind: '$kind' (count, toggle or choice)")
            }
            o.end()
            return out
        }
    }
}

class LayoutFileError(message: String) : Exception(message)

/**
 * A JSON object read strictly: each key read as the kind asked for, and [end] refuses any key nobody read. [at] is where
 * the object is in the file, for the messages.
 */
internal class Strict(private val o: JSONObject, private val at: String) {
    private val read = HashSet<String>()

    private fun where(k: String) = if (at.isEmpty()) k else "$at.$k"

    fun fail(message: String): Nothing = throw LayoutFileError(message)

    fun has(k: String): Boolean = o.has(k)

    fun optStr(k: String): String? {
        read += k
        if (!o.has(k)) return null
        return o.opt(k) as? String ?: fail("${where(k)}: not text")
    }

    fun str(k: String): String = optStr(k) ?: fail("${where(k)}: missing")

    fun optInt(k: String): Int? {
        read += k
        if (!o.has(k)) return null
        return when (val v = o.opt(k)) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> if (v % 1.0 == 0.0) v.toInt() else fail("${where(k)}: not a whole number")
            else -> fail("${where(k)}: not a number")
        }
    }

    fun int(k: String): Int = optInt(k) ?: fail("${where(k)}: missing")

    fun optBool(k: String): Boolean? {
        read += k
        if (!o.has(k)) return null
        return o.opt(k) as? Boolean ?: fail("${where(k)}: not true or false")
    }

    fun bool(k: String): Boolean {
        read += k
        if (!o.has(k)) fail("${where(k)}: missing")
        return o.opt(k) as? Boolean ?: fail("${where(k)}: not true or false")
    }

    /** The raw value of [k] (read), or null when absent. */
    fun raw(k: String): Any? { read += k; return if (o.has(k)) o.opt(k) else null }

    fun obj(k: String): Strict {
        read += k
        val v = o.opt(k) ?: fail("${where(k)}: missing")
        return Strict(v as? JSONObject ?: fail("${where(k)}: not an object"), where(k))
    }

    fun objs(k: String): List<Strict> {
        read += k
        val a = o.opt(k) as? JSONArray ?: fail("${where(k)}: ${if (o.has(k)) "not a list" else "missing"}")
        return (0 until a.length()).map { i -> Strict(a.opt(i) as? JSONObject ?: fail("${where(k)}[$i]: not an object"), "${where(k)}[$i]") }
    }

    fun strs(k: String): List<String> {
        read += k
        val a = o.opt(k) as? JSONArray ?: fail("${where(k)}: ${if (o.has(k)) "not a list" else "missing"}")
        return (0 until a.length()).map { i -> a.opt(i) as? String ?: fail("${where(k)}[$i]: not text") }
    }

    /** Refuses the keys nobody read (a typo, or something this build does not know). */
    fun end() {
        val extra = o.keys().asSequence().filter { it !in read }.toList()
        if (extra.isNotEmpty()) fail("${if (at.isEmpty()) "the file" else at}: unknown ${extra.joinToString { "'$it'" }}")
    }
}
