package dev.launcher.engine.kdl

/**
 * Writes a [KdlDocument] in kdl-org's canonical form (its test suite's translation rules): no comments, four-space
 * indents, arguments in order, properties in alphabetical order, children only when there are some, names bare unless
 * they must be quoted, every string a quoted one with escapes, integers in decimal and exponents as `E` with a sign.
 */
object KdlWriter {
    fun write(doc: KdlDocument): String =
        StringBuilder().also { out -> doc.nodes.forEach { node(out, it, 0) } }.ifEmpty { "\n" }.toString()

    private fun node(out: StringBuilder, n: KdlNode, depth: Int) {
        out.append("    ".repeat(depth))
        type(out, n.type)
        out.append(string(n.name))
        n.args.forEach { out.append(' '); value(out, it) }
        n.props.values.sortedBy { it.key }.forEach { out.append(' ').append(string(it.key)).append('='); value(out, it.value) }
        if (n.children.isNotEmpty()) {
            out.append(" {\n")
            n.children.forEach { node(out, it, depth + 1) }
            out.append("    ".repeat(depth)).append('}')
        }
        out.append('\n')
    }

    private fun type(out: StringBuilder, type: String?) { if (type != null) out.append('(').append(string(type)).append(')') }

    private fun value(out: StringBuilder, v: KdlValue) {
        type(out, v.type)
        out.append(when (v) {
            is KdlValue.Str -> string(v.value)
            is KdlValue.Bool -> "#${v.value}"
            is KdlValue.Null -> "#null"
            is KdlValue.Num -> number(v)
        })
    }

    private fun number(v: KdlValue.Num): String {
        v.integer()?.let { return it.toString() }
        if (v.raw.startsWith("#")) return v.raw
        val d = v.raw.replace("_", "").removePrefix("+")
        val e = d.indexOfFirst { it == 'e' || it == 'E' }
        if (e < 0) return d
        val exp = d.substring(e + 1)
        return d.substring(0, e) + "E" + (if (exp[0] == '+' || exp[0] == '-') exp else "+$exp")
    }

    /** [s] bare when it reads back as that identifier, else quoted. */
    fun string(s: String): String =
        if (s.isNotEmpty() && s[0] != '"' && s[0] != '#' && runCatching { Bare(s).string() == s }.getOrDefault(false)) s
        else quote(s)

    private class Bare(s: String) : KdlScanner(s)

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
            '\t' -> append("\\t"); '\b' -> append("\\b"); '\u000C' -> append("\\f")
            else -> if (c.code < 0x20 || c == '\u007F' || c == '\u0085' || c in '\u2028'..'\u2029' ||
                c in '\u200E'..'\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\uFEFF')
                append("\\u{").append(Integer.toHexString(c.code)).append('}') else append(c)
        }
        append('"')
    }
}
