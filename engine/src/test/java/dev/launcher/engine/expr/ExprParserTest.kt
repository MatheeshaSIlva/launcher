package dev.launcher.engine.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** E2 slice 4a: expressions' grammar, templates and every error with its offset. */
class ExprParserTest {
    /** The tree, fully bracketed, so precedence shows. */
    private fun show(n: Node): String = when (n) {
        is Node.Num -> (if (n.value % 1.0 == 0.0) n.value.toLong().toString() else n.value.toString()) + (n.unit ?: "")
        is Node.Str -> "'${n.value}'"
        is Node.Bool -> "#${n.value}"
        is Node.Null -> "#null"
        is Node.State -> "$${n.name}"
        is Node.Ref -> "@${n.name}"
        is Node.Path -> n.path
        is Node.Unary -> "(${n.op}${show(n.operand)})"
        is Node.Binary -> "(${show(n.left)} ${n.op} ${show(n.right)})"
        is Node.Cond -> "(${show(n.test)} ? ${show(n.then)} : ${show(n.otherwise)})"
        is Node.Pipe -> "(${show(n.value)} | ${n.formatter}${n.arg?.let { ":" + show(it) } ?: ""})"
    }
    private fun tree(text: String) = show(ExprParser.template("{$text}").whole!!)
    private fun error(text: String): Pair<String, Int> = try {
        ExprParser.template(text); fail("no error for $text"); "" to 0
    } catch (e: ExprException) { e.message!! to e.at }

    @Test fun precedence() {
        assertEquals("(1 + (2 * 3))", tree("1 + 2 * 3"))
        assertEquals("((1 - 2) - 3)", tree("1 - 2 - 3"))
        assertEquals("(((1 + 2) * 3) % 4)", tree("(1 + 2) * 3 % 4"))
        assertEquals("((\$a < 3) || ((\$b == 2) && (!\$c)))", tree("\$a < 3 || \$b == 2 && !\$c"))
        assertEquals("((-battery.level) >= 20)", tree("-battery.level >= 20"))
        assertEquals("((\$a > 1) ? 'x' : (\$b ? 'y' : 'z'))", tree("\$a > 1 ? 'x' : \$b ? 'y' : 'z'"))
        assertEquals("(((battery.level / 100) | percent) | pad:3)", tree("battery.level / 100 | percent | pad:3"))
        assertEquals("((\$x ? 1 : 2) | number:1)", tree("\$x ? 1 : 2 | number:1"))
        assertEquals("(\$a != #null)", tree("\$a != #null"))
        assertEquals("(\$a <= (\$b | pad:2))", tree("\$a <= (\$b | pad:2)"))
    }

    @Test fun valuesNamesAndUnits() {
        assertEquals("(battery.level * 0.27pt)", tree("battery.level * 0.27pt"))
        assertEquals("(12dp + 4px)", tree("12dp + 4px"))
        assertEquals("@comp.statusbar.battery-low-color", tree("@comp.statusbar.battery-low-color"))
        assertEquals("(@show-percent && #true)", tree("@show-percent && #true"))
        assertEquals("(\$a - \$b)", tree("\$a - \$b"))
        assertEquals("('H:mm' == \"x\")".replace('"', '\''), tree("'H:mm' == \"x\""))
        assertEquals("(now | date:'EEE d MMM')", tree("now | date:'EEE d MMM'"))
        assertEquals(Node.Num(250.0, "ms", 1), ExprParser.template("{250ms}").whole)
    }

    @Test fun templatesMixTextAndHoles() {
        val t = ExprParser.template("Battery {battery.level}% ({\$state})")
        assertEquals(listOf("Battery ", "% (", ")"), t.texts)
        assertEquals(listOf("battery.level", "\$state"), t.holes.map(::show))
        assertNull(t.whole)
        assertEquals(Node.Path("battery.level", 1), ExprParser.template("{battery.level}").whole)
        assertEquals(listOf("plain } text"), ExprParser.template("plain } text").texts)
        assertEquals(Node.Num(3.0, null, 3), ExprParser.template("{  3 }").whole)
    }

    @Test fun errorsSayWhatAndWhere() {
        assertEquals("Unclosed '{': a '}' must end the expression" to 4, error("abc {1 + 2"))
        assertEquals("Expected a value, found '}'" to 5, error("{1 + }"))
        assertEquals("Expected a value, found the end" to 3, error("{1+"))
        assertEquals("Expected an operator or '}', found '='" to 4, error("{\$a = 1}"))
        assertEquals("Expected ')', found '}'" to 7, error("{(1 + 2}"))
        assertEquals("Expected ':' to go with the '?', found '}'" to 8, error("{\$a ? 1 }"))
        assertEquals("Unclosed string" to 12, error("{now | date:'H:mm}"))
        assertEquals("Unknown keyword '#yes': write #true, #false or #null" to 1, error("{#yes}"))
        assertEquals("Expected a state name after '$'" to 2, error("{$ }"))
        assertEquals("'\$level' is a plain value: only a source path has members" to 7, error("{\$level.x}"))
        val unit = try { ExprParser.template("{4em}"); null } catch (e: ExprException) { e }!!
        assertEquals("Unknown unit 'em': pt, dp, px, ms, s" to 2, unit.message to unit.at)
        val formatter = try { ExprParser.template("{now | dat:'H'}"); null } catch (e: ExprException) { e }!!
        assertEquals(Triple("Unknown formatter 'dat'", 7, "did you mean 'date'?"), Triple(formatter.message, formatter.at, formatter.hint))
        assertEquals("'percent' takes no argument" to 25, error("{battery.level | percent:2}"))
        assertEquals("'pad' takes a width: pad:2" to 9, error("{\$n | pad}"))
        assertEquals("'date' takes a pattern in quotes: date:'H:mm'" to 12, error("{now | date:3}"))
        assertEquals("Expected a formatter after '|', found '}'" to 6, error("{\$n | }"))
    }
}
