package dev.launcher.engine.kdl

/**
 * KDL 2.0's parser (the grammar of kdl-org/kdl's draft-marchan-kdl2.md), our own (docs/BENCH_STEP0.md §2), over
 * [KdlScanner]: the version marker, nodes, type annotations, arguments, properties, children, keywords, numbers in every
 * radix and slashdash, with a span on every node, name, type, key and value. Checked against kdl-org's own test suite
 * (KdlSuiteTest).
 */
class KdlParser private constructor(text: String) : KdlScanner(text) {
    companion object {
        fun parse(text: String): KdlDocument = KdlParser(text).run {
            if (ch() == '\uFEFF') i++
            checkLiterals()
            val version = version()
            KdlDocument(nodes(top = true), version)
        }
    }

    /** `/- kdl-version 1|2` and a newline at the start: the version it names (the marker is a slashdashed node too). */
    private fun version(): Int? {
        if (!at("/-")) return null
        var k = i + 2
        fun spaces(): Boolean { val from = k; while (isSpace(s.getOrNull(k))) k++; return k > from }
        spaces()
        if (!s.startsWith("kdl-version", k)) return null
        k += "kdl-version".length
        if (!spaces()) return null
        val v = s.getOrNull(k)?.takeIf { it == '1' || it == '2' } ?: return null
        k++
        spaces()
        return if (newlineAt(k) > 0) v - '0' else null
    }

    private fun nodes(top: Boolean): List<KdlNode> {
        val out = ArrayList<KdlNode>()
        while (true) {
            lineSpace()
            when {
                i >= s.length -> if (top) return out else fail("Missing closing brace at the end of children list")
                ch() == '}' -> if (top) fail("Unexpected '}' outside a children list") else { i++; return out }
                at("/-") -> { i += 2; lineSpace(); node(top) }
                else -> out += node(top)
            }
        }
    }

    private fun node(top: Boolean): KdlNode {
        val start = i
        val type = type()
        if (type != null) nodeSpace()
        val nameStart = i
        val name = string() ?: fail("Expected a node name, found ${describe()}")
        val nameSpan = spanFrom(nameStart)
        val args = ArrayList<KdlValue>()
        val props = LinkedHashMap<String, KdlProp>()
        var children: List<KdlNode> = emptyList()
        var hasChildren = false
        var pastEntries = false   // a children list, kept or slashdashed, ends the arguments and properties
        var end = i
        while (true) {
            val spaced = nodeSpace()
            val c = ch()
            when {
                c == null || c == ';' || c == '}' || newlineAt(i) > 0 || at("//") -> {
                    if (c == '}' && top) fail("Unexpected '}' outside a children list")
                    if (c == ';') i++ else if (newlineAt(i) > 0) i += newlineAt(i) else if (at("//")) lineComment()
                    val span = lineCol(start).let { (l, col) -> Span(start, end, l, col) }
                    return KdlNode(name, nameSpan, args, props, children, span, type?.first, type?.second)
                }
                c == '{' -> {
                    if (hasChildren) fail("A node can have only one children list")
                    i++
                    children = nodes(top = false)
                    hasChildren = true; pastEntries = true; end = i
                }
                at("/-") -> {
                    i += 2
                    lineSpace()
                    if (ch() == '{') { i++; nodes(top = false); pastEntries = true }
                    else if (pastEntries) fail("Arguments and properties must come before children") else entry()
                }
                pastEntries -> fail("Arguments and properties must come before children")
                !spaced -> fail("Expected whitespace before an argument or property, found ${describe()}")
                else -> { entry().let { if (it is KdlProp) props[it.key] = it else args += it as KdlValue }; end = i }
            }
        }
    }

    /** `(name)`, with space allowed inside: the name and its span, or null when no type starts here. */
    private fun type(): Pair<String, Span>? {
        if (ch() != '(') return null
        i++
        nodeSpace()
        val start = i
        val name = string() ?: fail("Expected a type name, found ${describe()}")
        val span = spanFrom(start)
        nodeSpace()
        if (ch() != ')') fail("Expected ')' after the type name, found ${describe()}")
        i++
        return name to span
    }

    private fun keywordAhead() = ch() == '#' && ch(1) != '"' && ch(1) != '#'

    /** An argument (a [KdlValue]) or a property (a [KdlProp]). */
    private fun entry(): Any {
        if (ch() == '(' || keywordAhead() || numberAhead()) return value()
        val start = i
        val key = string() ?: fail("Expected a value, found ${describe()}")
        val keySpan = spanFrom(start)
        val after = i
        nodeSpace()
        if (ch() == '=') { i++; nodeSpace(); return KdlProp(key, keySpan, value()) }
        i = after
        return KdlValue.Str(key, keySpan)
    }

    private fun value(): KdlValue {
        val type = type()
        if (type != null) nodeSpace()
        val (t, ts) = type?.first to type?.second
        val start = i
        return when {
            keywordAhead() -> keyword(start, t, ts)
            numberAhead() -> number(start, t, ts)
            else -> KdlValue.Str(string() ?: fail("Expected a value, found ${describe()}"), spanFrom(start), t, ts)
        }
    }

    private fun keyword(start: Int, t: String?, ts: Span?): KdlValue {
        i++
        while (isIdentChar(ch())) i++
        return when (val word = s.substring(start + 1, i)) {
            "true" -> KdlValue.Bool(true, spanFrom(start), t, ts)
            "false" -> KdlValue.Bool(false, spanFrom(start), t, ts)
            "null" -> KdlValue.Null(spanFrom(start), t, ts)
            "inf", "-inf", "nan" -> KdlValue.Num("#$word", spanFrom(start), t, ts)
            else -> fail("Unknown keyword '#$word'", start)
        }
    }

    private fun numberAhead(): Boolean {
        val c = ch() ?: return false
        return c in '0'..'9' || (c == '+' || c == '-') && ch(1).let { it != null && it in '0'..'9' }
    }

    private fun number(start: Int, t: String?, ts: Span?): KdlValue.Num {
        if (ch() == '+' || ch() == '-') i++
        val radix = when { at("0x") -> 16; at("0o") -> 8; at("0b") -> 2; else -> 10 }
        fun digit(c: Char?) = c != null && c.code < 128 && Character.digit(c, radix) >= 0
        fun digits(what: String) {
            if (!digit(ch())) fail("Invalid number: expected $what digits, found ${describe()}")
            while (digit(ch()) || ch() == '_') i++
        }
        if (radix != 10) { i += 2; digits(when (radix) { 16 -> "hexadecimal"; 8 -> "octal"; else -> "binary" }) }
        else {
            digits("decimal")
            if (ch() == '.' && ch(1).let { it != null && it in '0'..'9' }) { i++; digits("decimal") }
            if (ch() == 'e' || ch() == 'E') { i++; if (ch() == '+' || ch() == '-') i++; digits("exponent") }
        }
        if (isIdentChar(ch())) fail("Invalid number: unexpected '${ch()}'")
        return KdlValue.Num(s.substring(start, i), spanFrom(start), t, ts)
    }
}
