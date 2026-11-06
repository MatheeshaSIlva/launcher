package dev.launcher.engine.kdl

/**
 * KDL 2.0's characters (kdl-org/kdl's draft-marchan-kdl2.md) for [KdlParser]'s grammar: positions and errors, whitespace,
 * newlines, comments and strings. One pass over the text, no regex; an error says what and where ("... at LINE:COL").
 * Raw and multi-line strings and line continuations come in E2 slice 2: until then they are refused with an error naming them.
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
    /** Whitespace and block comments inside a node; true if any. */
    internal fun nodeSpace(): Boolean {
        val from = i
        while (true) when {
            isSpace(ch()) -> i++
            at("/*") -> blockComment()
            at("\\") -> fail("Line continuations are not supported yet (E2 slice 2)")
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
    /** An identifier or a quoted string, or null when neither starts here. */
    internal fun string(): String? = when {
        at("\"\"\"") -> fail("Multi-line strings are not supported yet (E2 slice 2)")
        ch() == '"' -> quoted()
        ch() == '#' && (ch(1) == '"' || ch(1) == '#') -> fail("Raw strings are not supported yet (E2 slice 2)")
        else -> identifier()
    }

    private fun identifier(): String? {
        val start = i
        while (isIdentChar(ch())) i++
        if (i == start) return null
        val word = s.substring(start, i)
        val (first, second) = word[0] to word.getOrNull(1)
        if (first.isDigit() || first == '.' && second?.isDigit() == true ||
            (first == '+' || first == '-') && (second?.isDigit() == true || second == '.' && word.getOrNull(2)?.isDigit() == true))
            fail("Invalid identifier '$word': it cannot start like a number", start)
        if (word in KEYWORDS) fail("Bare '$word' is not a value in KDL 2: write #$word", start)
        return word
    }

    private fun quoted(): String {
        val start = i
        i++
        val out = StringBuilder()
        while (true) {
            val c = ch() ?: fail("Unclosed string", start)
            when {
                c == '"' -> { i++; return out.toString() }
                newlineAt(i) > 0 -> fail("A quoted string cannot hold a newline: write \\n, or use a multi-line string")
                c == '\\' -> escape(out)
                disallowed(c) -> fail("Character U+%04X is not allowed in a string".format(c.code))
                else -> { out.append(c); i++ }
            }
        }
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
