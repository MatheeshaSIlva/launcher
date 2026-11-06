package dev.launcher.engine.kdl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** E2 slice 1b: KDL 2's node grammar, keywords and numbers, spans and errors (docs/E2_PLAN.md). */
class KdlParserTest {
    private fun parse(text: String) = KdlParser.parse(text).nodes
    private fun error(text: String): String = try {
        KdlParser.parse(text); fail("no error for: $text"); ""
    } catch (e: KdlException) { e.message!! }
    private fun str(v: KdlValue) = (v as KdlValue.Str).value
    private fun num(v: KdlValue) = v as KdlValue.Num

    @Test fun nodesArgumentsPropertiesChildren() {
        val n = parse("bar side=left 3 \"two words\" {\n  time size=15\n  battery\n}\n").single()
        assertEquals("bar", n.name)
        assertEquals(listOf(3L), n.args.filterIsInstance<KdlValue.Num>().map { it.long })
        assertEquals("two words", str(n.args[1]))
        assertEquals("left", str(n.props.getValue("side").value))
        assertEquals(listOf("time", "battery"), n.children.map { it.name })
        assertEquals(15L, num(n.children[0].props.getValue("size").value).long)
    }

    @Test fun terminators() {
        assertEquals(listOf("a", "b", "c", "d"), parse("a; b\nc // comment\nd").map { it.name })
        assertEquals(listOf("a", "b"), parse("a { x }; b").map { it.name })
        assertEquals(emptyList<KdlNode>(), parse("  \n// only a comment\n/* and a block */"))
        assertEquals("a", parse("a {x;y}").single().name)
        assertEquals(listOf("x", "y"), parse("a {x;y}").single().children.map { it.name })
    }

    @Test fun propertiesSpacingAndTheRightmostWins() {
        val n = parse("n a = 1 a=2 \"b c\"=#true").single()
        assertEquals(2L, num(n.props.getValue("a").value).long)
        assertEquals(KdlValue.Bool(true, n.props.getValue("b c").value.span), n.props.getValue("b c").value)
        assertEquals("v", str(parse("n k = v").single().props.getValue("k").value))
    }

    @Test fun keywords() {
        val args = parse("n #true #false #null #inf #-inf #nan").single().args
        assertEquals(true, (args[0] as KdlValue.Bool).value)
        assertEquals(false, (args[1] as KdlValue.Bool).value)
        assertTrue(args[2] is KdlValue.Null)
        assertEquals(Double.POSITIVE_INFINITY, num(args[3]).double, 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, num(args[4]).double, 0.0)
        assertTrue(num(args[5]).double.isNaN())
        assertTrue(error("n #yes").startsWith("Unknown keyword '#yes' at 1:3"))
    }

    @Test fun numbersKeepTheirRawTextAndValue() {
        val a = parse("n 1_000 -12 +7 3.25 1e3 -2.5E-2 0xFF_ff 0o17 0b1010 -0x10 99999999999999999999").single().args.map(::num)
        assertEquals(listOf("1_000", "-12", "+7", "3.25", "1e3", "-2.5E-2", "0xFF_ff", "0o17", "0b1010", "-0x10",
            "99999999999999999999"), a.map { it.raw })
        assertEquals(listOf(1000L, -12L, 7L, null, null, null, 65535L, 15L, 10L, -16L, null), a.map { it.long })
        assertEquals(1000.0, a[4].double, 0.0)
        assertEquals(-0.025, a[5].double, 1e-12)
        assertEquals(1e20, a[10].double, 1e5)
    }

    @Test fun badNumbers() {
        assertTrue(error("n 1.").startsWith("Invalid number: unexpected '.' at 1:4"))
        assertTrue(error("n 12abc").startsWith("Invalid number: unexpected 'a' at 1:5"))
        assertTrue(error("n 0x").startsWith("Invalid number: expected hexadecimal digits"))
        assertTrue(error("n 1e").startsWith("Invalid number: expected exponent digits"))
        assertTrue(error("n 0b102").startsWith("Invalid number: unexpected '2'"))
    }

    @Test fun slashdash() {
        val n = parse("/-gone 1\nkept /-1 2 /-k=v k2=v2 /-{ hidden } {\n  /-child\n  child2\n} /-{ also }").single()
        assertEquals("kept", n.name)
        assertEquals(listOf(2L), n.args.map { num(it).long })
        assertEquals(setOf("k2"), n.props.keys)
        assertEquals(listOf("child2"), n.children.map { it.name })
        assertEquals("a", parse("/- \n gone\na").single().name)
    }

    @Test fun spans() {
        val n = parse("// top\n  bar  side=left 12 {\n    x\n  }\nnext").first()
        assertEquals(Span(9, 38, 2, 3), n.span)
        assertEquals(Span(9, 12, 2, 3), n.nameSpan)
        val side = n.props.getValue("side")
        assertEquals(Span(14, 18, 2, 8), side.keySpan)
        assertEquals(Span(19, 23, 2, 13), side.value.span)
        assertEquals(Span(24, 26, 2, 18), n.args[0].span)
        assertEquals(Span(33, 34, 3, 5), n.children[0].nameSpan)
    }

    @Test fun errorsSayWhatAndWhere() {
        assertEquals("Missing closing brace at the end of children list at 3:1", error("a {\n  b\n"))
        assertEquals("Unexpected '}' outside a children list at 2:1", error("box\n}"))
        assertEquals("Expected whitespace before an argument or property, found '\"' at 1:3", error("n1\"x\""))
        assertEquals("Arguments and properties must come before children at 1:7", error("n { } 1"))
        assertEquals("A node can have only one children list at 1:6", error("n {} {}"))
        assertTrue(error("n = 1").startsWith("Expected a value, found '='"))
        assertTrue(error("= x").startsWith("Expected a node name, found '='"))
        assertTrue(error("(dp)n").startsWith("Type annotations are not supported yet"))
        assertTrue(error("n (dp)4").startsWith("Type annotations are not supported yet"))
    }

    @Test fun theSpecsComponentExampleAsStep0CorrectedItParsesUpToItsScript() {
        // docs/BENCH_STEP0.md §2: the spec's example with #false; its script is a raw string (slice 2), so it stops there.
        val text = """
            component "battery-ring" version="1.2.0" {
              props {
                size      type=dp    default=48
                warn      type=int   default=20
              }
              uses { source battery }
              state {
                level 100
                charging #false
              }
            }
        """.trimIndent()
        val c = parse(text).single()
        assertEquals("battery-ring", str(c.args[0]))
        assertEquals(listOf("props", "uses", "state"), c.children.map { it.name })
        assertEquals(false, (c.children[2].children[1].args[0] as KdlValue.Bool).value)
    }
}
