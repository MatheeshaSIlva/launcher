package dev.launcher.engine.kdl

/**
 * KDL 2.0's parser (the grammar of kdl-org/kdl's draft-marchan-kdl2.md), our own (docs/BENCH_STEP0.md §2), over
 * [KdlScanner]: nodes, arguments, properties, children, keywords, numbers in every radix and slashdash, with a span on
 * every node, name, key and value. Type annotations and the version marker come in E2 slice 2 (refused until then).
 */
class KdlParser private constructor(text: String) : KdlScanner(text) {
    companion object {
        fun parse(text: String): KdlDocument = KdlParser(text).run { KdlDocument(nodes(top = true)) }
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
        if (ch() == '(') fail("Type annotations are not supported yet (E2 slice 2)")
        val name = string() ?: fail("Expected a node name, found ${describe()}")
        val nameSpan = spanFrom(start)
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
                    return KdlNode(name, nameSpan, args, props, children, Span(start, end, nameSpan.line, nameSpan.col))
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

    /** An argument (a [KdlValue]) or a property (a [KdlProp]). */
    private fun entry(): Any {
        val start = i
        if (ch() == '"' || isIdentChar(ch()) && !numberAhead()) {
            val key = string()!!
            val keySpan = spanFrom(start)
            val after = i
            nodeSpace()
            if (ch() == '=') { i++; nodeSpace(); return KdlProp(key, keySpan, value()) }
            i = after
            return KdlValue.Str(key, keySpan)
        }
        return value()
    }

    private fun value(): KdlValue {
        val start = i
        return when {
            ch() == '(' -> fail("Type annotations are not supported yet (E2 slice 2)")
            ch() == '#' && ch(1) != '"' && ch(1) != '#' -> keyword(start)
            numberAhead() -> number(start)
            else -> KdlValue.Str(string() ?: fail("Expected a value, found ${describe()}"), spanFrom(start))
        }
    }

    private fun keyword(start: Int): KdlValue {
        i++
        while (isIdentChar(ch())) i++
        return when (val word = s.substring(start + 1, i)) {
            "true" -> KdlValue.Bool(true, spanFrom(start))
            "false" -> KdlValue.Bool(false, spanFrom(start))
            "null" -> KdlValue.Null(spanFrom(start))
            "inf", "-inf", "nan" -> KdlValue.Num("#$word", spanFrom(start))
            else -> fail("Unknown keyword '#$word'", start)
        }
    }

    private fun numberAhead(): Boolean {
        val c = ch() ?: return false
        return c.isDigit() || (c == '+' || c == '-') && ch(1)?.isDigit() == true
    }

    private fun number(start: Int): KdlValue.Num {
        if (ch() == '+' || ch() == '-') i++
        val radix = when { at("0x") -> 16; at("0o") -> 8; at("0b") -> 2; else -> 10 }
        fun digit(c: Char?) = c != null && Character.digit(c, radix) >= 0
        fun digits(what: String) {
            if (!digit(ch())) fail("Invalid number: expected $what digits, found ${describe()}")
            while (digit(ch()) || ch() == '_') i++
        }
        if (radix != 10) { i += 2; digits(when (radix) { 16 -> "hexadecimal"; 8 -> "octal"; else -> "binary" }) }
        else {
            digits("decimal")
            if (ch() == '.' && ch(1)?.isDigit() == true) { i++; digits("decimal") }
            if (ch() == 'e' || ch() == 'E') { i++; if (ch() == '+' || ch() == '-') i++; digits("exponent") }
        }
        if (isIdentChar(ch())) fail("Invalid number: unexpected '${ch()}'")
        return KdlValue.Num(s.substring(start, i), spanFrom(start))
    }
}
