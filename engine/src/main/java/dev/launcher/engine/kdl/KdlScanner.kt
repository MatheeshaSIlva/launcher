package dev.launcher.engine.kdl

/**
 * KDL 2.0's characters (kdl-org/kdl's draft-marchan-kdl2.md) for [KdlParser]'s grammar: positions and errors, whitespace,
 * newlines, line continuations, comments and every kind of string (identifier, quoted, raw, multi-line with its dedent).
 * One pass over the text, no regex; an error says what and where ("... at LINE:COL").
 */
open class KdlScanner(protected val s: String) {
    private companion object {
        val KEYWORDS = setOf("true", "false", "null", "inf", "-inf", "nan")
        const val NON_IDENT = "\\/(){};[]\"#="
    }

    internal var i = 0
    private val lineStarts: IntArray = buildList {
        add(0)
        var k = 0
        while (k < s.length) { val n = newlineAt(k); if (n > 0) { k += n; add(k) } else k++ }
    }.toIntArray()

    // ------------------------------------------------------------------------------------------------- positions, errors
    internal fun spanFrom(start: Int): Span = lineCol(start).let { (l, c) -> Span(start, i, l, c) }
    internal fun lineCol(at: Int): Pair<Int, Int> {
        val line = lineStarts.binarySearch(at).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        return line + 1 to at - lineStarts[line] + 1
    }
    internal fun fail(message: String, at: Int = i): Nothing =
        lineCol(at).let { (l, c) -> throw KdlException("$message at $l:$c", l, c) }

    /** A disallowed code point anywhere from [i] on (comments included) is an error; a BOM is allowed only first. */
    internal fun checkLiterals() {
        for (k in i until s.length) {
            val c = s[k]
            val lone = c.isHighSurrogate() && s.getOrNull(k + 1)?.isLowSurrogate() != true ||
                c.isLowSurrogate() && s.getOrNull(k - 1)?.isHighSurrogate() != true
            if (disallowed(c) || lone) fail("Character U+%04X is not allowed in a KDL document".format(c.code), k)
        }
    }

    // ------------------------------------------------------------------------------------------------- characters
    internal fun ch(k: Int = 0): Char? = s.getOrNull(i + k)
    internal fun at(text: String) = s.startsWith(text, i)
    /** The length of the newline at [k] (2 for CR LF), or 0. */
    internal fun newlineAt(k: Int): Int = when (s.getOrNull(k)) {
        '\r' -> if (s.getOrNull(k + 1) == '\n') 2 else 1
        '\n', '\u0085', '\u000B', '\u000C', '\u2028', '\u2029' -> 1
        else -> 0
    }
    internal fun isSpace(c: Char?) = c != null && (c == '\t' || c == ' ' || c == '\u00A0' || c == '\u1680' ||
        c in '\u2000'..'\u200A' || c == '\u202F' || c == '\u205F' || c == '\u3000')
    internal fun disallowed(c: Char) = c in '\u0000'..'\u0008' || c in '\u000E'..'\u001F' || c == '\u007F' ||
        c in '\u200E'..'\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\uFEFF'
    internal fun isIdentChar(c: Char?) = c != null && !isSpace(c) && c !in NON_IDENT && !disallowed(c) &&
        c != '\r' && c != '\n' && c != '\u0085' && c != '\u000B' && c != '\u000C' && c != '\u2028' && c != '\u2029'

    internal fun describe(): String = when (val c = ch()) {
        null -> "end of file"
        else -> if (newlineAt(i) > 0) "newline" else "'$c'"
    }

    // ------------------------------------------------------------------------------------------------- space, comments
    /** Whitespace, block comments and line continuations inside a node; true if any. */
    internal fun nodeSpace(): Boolean {
        val from = i
        while (true) when {
            isSpace(ch()) -> i++
            at("/*") -> blockComment()
            at("\\") -> lineContinuation()
            else -> return i > from
        }
    }
    private fun blockComment() {
        val start = i
        i += 2
        var depth = 1
        while (depth > 0) when {
            i >= s.length -> fail("Unclosed block comment", start)
            at("/*") -> { depth++; i += 2 }
            at("*/") -> { depth--; i += 2 }
            else -> i++
        }
    }
    /** `\`, whitespace and block comments, then a line comment, a newline or the end of the text. */
    private fun lineContinuation() {
        i++
        while (true) when {
            isSpace(ch()) -> i++
            at("/*") -> blockComment()
            else -> break
        }
        when {
            at("//") -> lineComment()
            newlineAt(i) > 0 -> i += newlineAt(i)
            i < s.length -> fail("A line continuation '\\' must end its line, found ${describe()}")
        }
    }
    internal fun lineComment() { while (i < s.length && newlineAt(i) == 0) i++; i += newlineAt(i) }
    /** Whitespace, newlines and both kinds of comment, between nodes. */
    internal fun lineSpace() {
        while (true) when {
            nodeSpace() -> {}
            newlineAt(i) > 0 -> i += newlineAt(i)
            at("//") -> lineComment()
            else -> return
        }
    }

    // ------------------------------------------------------------------------------------------------- strings
    /** An identifier, a quoted or a raw string (single- or multi-line), or null when none starts here. */
    internal fun string(): String? {
        var hashes = 0
        while (ch(hashes) == '#') hashes++
        return when {
            ch(hashes) != '"' -> if (hashes > 0) null else identifier()
            s.startsWith("\"\"\"", i + hashes) -> multiLine(hashes)
            else -> quoted(hashes)
        }
    }

    private fun identifier(): String? {
        val start = i
        while (isIdentChar(ch())) i++
        if (i == start) return null
        val word = s.substring(start, i)
        fun digit(k: Int) = word.getOrNull(k).let { it != null && it in '0'..'9' }
        val first = word[0]
        if (digit(0) || first == '.' && digit(1) || (first == '+' || first == '-') && (digit(1) || word.getOrNull(1) == '.' && digit(2)))
            fail("Invalid identifier '$word': it cannot start like a number", start)
        if (word in KEYWORDS) fail("Bare '$word' is not a value in KDL 2: write #$word", start)
        return word
    }

    /** Whether the quotes at [i] + [quotes] are followed by [hashes] `#`: a raw string's close. */
    private fun closes(quotes: Int, hashes: Int) = (0 until hashes).all { ch(quotes + it) == '#' }

    /** `"..."`, or raw with [hashes] `#` around it (no escapes). */
    private fun quoted(hashes: Int): String {
        val start = i
        i += hashes + 1
        val out = StringBuilder()
        while (true) {
            val c = ch() ?: fail("Unclosed string", start)
            when {
                c == '"' && closes(1, hashes) -> { i += 1 + hashes; return out.toString() }
                newlineAt(i) > 0 -> fail("A quoted string cannot hold a newline: write \\n, or use a multi-line string")
                c == '\\' && hashes == 0 -> escape(out)
                else -> { out.append(c); i++ }
            }
        }
    }

    /**
     * `"""` and a newline, lines, then a last line of whitespace only and `"""` (raw with [hashes] `#`). Escapes are read
     * first (whitespace escapes vanish); the characters an escape made are marked, so that they never count as a line
     * break or as indentation when the lines are dedented by the last line's whitespace.
     */
    private fun multiLine(hashes: Int): String {
        val start = i
        i += hashes + 3
        if (newlineAt(i) == 0) fail("A multi-line string must start with a newline right after its opening quotes", start)
        i += newlineAt(i)
        val text = StringBuilder()
        val escaped = java.util.BitSet()
        while (true) {
            val c = ch() ?: fail("Unclosed multi-line string", start)
            when {
                at("\"\"\"") && closes(3, hashes) -> { i += 3 + hashes; break }
                newlineAt(i) > 0 -> { text.append('\n'); i += newlineAt(i) }
                c == '\\' && hashes == 0 -> text.length.let { from -> escape(text); escaped.set(from, text.length) }
                else -> { text.append(c); i++ }
            }
        }
        fun blank(from: Int, to: Int) = (from until to).all { !escaped[it] && isSpace(text[it]) }
        val breaks = text.indices.filter { text[it] == '\n' && !escaped[it] }
        val last = (breaks.lastOrNull() ?: -1) + 1
        if (!blank(last, text.length)) fail("A multi-line string's closing quotes must be alone on their line", start)
        val indent = text.substring(last)
        val out = StringBuilder()
        var from = 0
        for ((n, to) in breaks.withIndex()) {
            if (n > 0) out.append('\n')
            if (!blank(from, to)) {
                if (!text.startsWith(indent, from) || (from until from + indent.length).any { escaped[it] })
                    fail("Every line of a multi-line string must start with its closing line's whitespace", start)
                out.append(text, from + indent.length, to)
            }
            from = to + 1
        }
        return out.toString()
    }

    private fun escape(out: StringBuilder) {
        val at = i
        i++
        when (val e = ch()) {
            'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t'); '\\' -> out.append('\\')
            '"' -> out.append('"'); 'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 's' -> out.append(' ')
            'u' -> {
                val close = if (ch(1) == '{') s.indexOf('}', i + 2) else -1
                val hex = if (close < 0) "" else s.substring(i + 2, close)
                val cp = if (hex.length in 1..6 && hex.all { Character.digit(it, 16) >= 0 }) hex.toInt(16) else -1
                if (cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) fail("Invalid unicode escape: expected \\u{1 to 6 hex digits}", at)
                out.appendCodePoint(cp)
                i = close
            }
            else -> if (isSpace(e) || newlineAt(i) > 0) {
                while (isSpace(ch()) || newlineAt(i) > 0) i += maxOf(1, newlineAt(i))
                return
            } else fail("Invalid escaped character '${e ?: "end of file"}'", at)
        }
        i++
    }
}
