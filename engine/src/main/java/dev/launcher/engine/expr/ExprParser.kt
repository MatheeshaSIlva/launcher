package dev.launcher.engine.expr

import dev.launcher.engine.model.nearest

/**
 * Reads an attribute's text into a [Template]: text with `{expression}` holes. A Pratt parser, lowest first: `|` pipes,
 * `?:`, `||`, `&&`, `== !=`, `< <= > >=`, `+ -`, `* / %`, unary `! -`, then values: numbers with an optional unit
 * (`0.27pt`), strings in single or double quotes, `#true #false #null`, `$state`, `@prop` or `@token`, source paths, and
 * parentheses. A name may hold `-` between letters (`@show-percent`), so subtraction of two names takes spaces.
 */
class ExprParser private constructor(private val s: String) {
    companion object {
        /** The formatters and what their argument is (null: none). */
        val FORMATTERS = mapOf("date" to "a pattern in quotes: date:'H:mm'", "percent" to null,
            "pad" to "a width: pad:2", "number" to "decimal places: number:1")
        val UNITS = listOf("pt", "dp", "px", "ms", "s")
        private val BINARY = mapOf("||" to 3, "&&" to 4, "==" to 5, "!=" to 5, "<" to 6, "<=" to 6, ">" to 6, ">=" to 6,
            "+" to 7, "-" to 7, "*" to 8, "/" to 8, "%" to 8)

        fun template(text: String): Template = ExprParser(text).template()
    }

    private var i = 0

    private fun fail(message: String, at: Int = i, hint: String? = null): Nothing = throw ExprException(message, at, hint)
    private fun here() = if (i >= s.length) "the end" else "'${s[i]}'"
    private fun at(text: String) = s.startsWith(text, i)
    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun letter(k: Int) = s.getOrNull(k).let { it != null && (it.isLetter() || it == '_') }

    private fun template(): Template {
        val texts = ArrayList<String>()
        val holes = ArrayList<Node>()
        val text = StringBuilder()
        while (i < s.length) {
            if (s[i] != '{') { text.append(s[i++]); continue }
            val open = i++
            texts += text.toString(); text.clear()
            holes += pipes()
            skip()
            if (i >= s.length) fail("Unclosed '{': a '}' must end the expression", open)
            if (s[i] != '}') fail("Expected an operator or '}', found ${here()}")
            i++
        }
        texts += text.toString()
        return Template(texts, holes)
    }

    private fun pipes(): Node {
        var node = expr(1)
        while (true) {
            skip()
            if (!at("|") || at("||")) return node
            val bar = i++
            skip()
            val nameAt = i
            val name = name(dots = false) ?: fail("Expected a formatter after '|', found ${here()}")
            if (name !in FORMATTERS) fail("Unknown formatter '$name'", nameAt, nearest(name, FORMATTERS.keys)?.let { "did you mean '$it'?" })
            val takes = FORMATTERS.getValue(name)
            val arg = if (at(":")) { i++; primary() } else null
            when {
                takes == null && arg != null -> fail("'$name' takes no argument", arg.at)
                takes != null && (arg == null || (name == "date") != (arg is Node.Str) || arg !is Node.Str && arg !is Node.Num) ->
                    fail("'$name' takes $takes", arg?.at ?: i)
            }
            node = Node.Pipe(node, name, arg, bar)
        }
    }

    private fun expr(min: Int): Node {
        var left = unary()
        while (true) {
            skip()
            if (min <= 2 && at("?")) {
                val q = i++
                val then = expr(2)
                skip()
                if (!at(":")) fail("Expected ':' to go with the '?', found ${here()}")
                i++
                left = Node.Cond(left, then, expr(2), q)
                continue
            }
            val op = listOf(s.substring(i, minOf(i + 2, s.length)), s.substring(i, minOf(i + 1, s.length))).firstOrNull { it in BINARY }
            val prec = op?.let { BINARY.getValue(it) } ?: return left
            if (prec < min) return left
            val opAt = i
            i += op.length
            left = Node.Binary(op, left, expr(prec + 1), opAt)
        }
    }

    private fun unary(): Node {
        skip()
        val opAt = i
        return when {
            at("!") && !at("!=") -> { i++; Node.Unary("!", unary(), opAt) }
            at("-") -> { i++; Node.Unary("-", unary(), opAt) }
            else -> primary()
        }
    }

    private fun primary(): Node {
        skip()
        val start = i
        val c = s.getOrNull(i) ?: fail("Expected a value, found the end")
        return when {
            c == '(' -> {
                i++
                pipes().also { skip(); if (!at(")")) fail("Expected ')', found ${here()}"); i++ }
            }
            c == '$' -> { i++; Node.State(name(dots = false) ?: fail("Expected a state name after '$'"), start).also { noMembers(it) } }
            c == '@' -> { i++; Node.Ref(name(dots = true) ?: fail("Expected a prop or token name after '@'"), start) }
            c == '#' -> { i++; val word = name(dots = false); when (word) {
                "true", "false" -> Node.Bool(word == "true", start)
                "null" -> Node.Null(start)
                else -> fail("Unknown keyword '#${word ?: ""}': write #true, #false or #null", start)
            } }
            c == '\'' || c == '"' -> {
                val close = s.indexOf(c, i + 1).takeIf { it >= 0 } ?: fail("Unclosed string", start)
                i = close + 1
                Node.Str(s.substring(start + 1, close), start)
            }
            c.isDigit() -> number(start)
            letter(i) -> Node.Path(name(dots = true)!!, start)
            else -> fail("Expected a value, found ${here()}")
        }
    }

    /** `.member` belongs to source paths only: state, props and tokens hold plain values. */
    private fun noMembers(n: Node.State) { if (at(".")) fail("'\$${n.name}' is a plain value: only a source path has members") }

    private fun number(start: Int): Node.Num {
        while (s.getOrNull(i)?.isDigit() == true) i++
        if (at(".") && s.getOrNull(i + 1)?.isDigit() == true) { i++; while (s.getOrNull(i)?.isDigit() == true) i++ }
        val value = s.substring(start, i).toDouble()
        val unitAt = i
        while (s.getOrNull(i)?.isLetter() == true) i++
        val unit = s.substring(unitAt, i).ifEmpty { null }
        if (unit != null && unit !in UNITS)
            fail("Unknown unit '$unit': ${UNITS.joinToString()}", unitAt, nearest(unit, UNITS)?.let { "did you mean '$it'?" })
        return Node.Num(value, unit, start)
    }

    /** Letters, digits, `_`, a `-` between letters or digits and a letter, and `.` between names when [dots]. */
    private fun name(dots: Boolean): String? {
        if (!letter(i)) return null
        val start = i
        while (true) {
            val c = s.getOrNull(i)
            when {
                c != null && (c.isLetterOrDigit() || c == '_') -> i++
                (c == '-' || dots && c == '.') && letter(i + 1) -> i++
                else -> return s.substring(start, i)
            }
        }
    }
}
