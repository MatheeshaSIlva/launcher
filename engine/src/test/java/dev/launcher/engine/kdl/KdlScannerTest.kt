package dev.launcher.engine.kdl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** E2 slice 1a: KDL 2's characters, strings, comments, positions and errors (docs/E2_PLAN.md). */
class KdlScannerTest {
    private class Scan(text: String) : KdlScanner(text)

    private fun str(text: String): String? = Scan(text).string()
    private fun error(block: () -> Unit): KdlException = try {
        block(); fail("no error"); throw AssertionError()
    } catch (e: KdlException) { e }

    @Test fun identifiers() {
        assertEquals("node", str("node rest"))
        assertEquals("--this", str("--this"))
        assertEquals(".md", str(".md"))
        assertEquals("-", str("-"))
        assertEquals("日本", str("日本 x"))
        assertEquals("a", str("a=1"))
        assertNull(str("{"))
    }

    @Test fun identifiersThatLookLikeNumbersOrKeywordsAreRefused() {
        assertTrue(error { str("-1a") }.message!!.startsWith("Invalid identifier '-1a'"))
        assertTrue(error { str(".5x") }.message!!.startsWith("Invalid identifier"))
        assertTrue(error { str("+.5") }.message!!.startsWith("Invalid identifier"))
        assertEquals("Bare 'true' is not a value in KDL 2: write #true at 1:1", error { str("true") }.message)
        assertEquals("Bare 'null' is not a value in KDL 2: write #null at 1:1", error { str("null") }.message)
    }

    @Test fun quotedStringsAndEscapes() {
        assertEquals("a b", str("\"a b\""))
        assertEquals("line\nnext\ttab\\\"q\" \u0008\u000C", str("\"line\\nnext\\ttab\\\\\\\"q\\\"\\s\\b\\f\""))
        assertEquals("é😀", str("\"\\u{e9}\\u{1F600}\""))
        assertEquals("Hello World", str("\"Hello \\    World\""))
        assertEquals("Hello\nWorld", str("\"Hello\\n\\\n    World\""))
        assertEquals("", str("\"\""))
    }

    @Test fun badStringsSayWhereAndWhy() {
        assertEquals("Invalid escaped character 'q' at 1:2", error { str("\"\\q\"") }.message)
        assertEquals("Unclosed string at 1:1", error { str("\"abc") }.message)
        assertTrue(error { str("\"a\nb\"") }.message!!.startsWith("A quoted string cannot hold a newline"))
        assertTrue(error { str("\"\\u{D800}\"") }.message!!.startsWith("Invalid unicode escape"))
        assertTrue(error { str("\"\\u{1234567}\"") }.message!!.startsWith("Invalid unicode escape"))
        assertTrue(error { str("\"\\u41\"") }.message!!.startsWith("Invalid unicode escape"))
    }

    @Test fun slice2SyntaxIsRefusedByName() {
        assertTrue(error { str("#\"raw\"#") }.message!!.startsWith("Raw strings"))
        assertTrue(error { str("\"\"\"\nx\n\"\"\"") }.message!!.startsWith("Multi-line strings"))
        assertTrue(error { Scan("  \\\n").nodeSpace() }.message!!.startsWith("Line continuations"))
    }

    @Test fun commentsAndSpace() {
        val s = Scan("  /* a /* nested */ comment */ \t// rest of line\r\n\u2028/**/x")
        s.lineSpace()
        assertEquals('x', s.ch())
        assertTrue(Scan("/* never closed").let { e -> error { e.lineSpace() } }.message!!.startsWith("Unclosed block comment at 1:1"))
    }

    @Test fun newlinesOfEveryKindCountForPositions() {
        val text = "a\r\nb\rc\nd\u0085e\u000Bf\u000Cg\u2028h\u2029i"
        val s = Scan(text)
        for ((ch, line) in listOf('a' to 1, 'b' to 2, 'c' to 3, 'd' to 4, 'e' to 5, 'f' to 6, 'g' to 7, 'h' to 8, 'i' to 9)) {
            assertEquals("line of $ch", line to 1, s.lineCol(text.indexOf(ch)))
        }
        assertEquals(1 to 3, Scan("ab cd").lineCol(2))
    }

    @Test fun spansCoverWhatWasRead() {
        val s = Scan("x\n  \"name\" rest")
        s.i = 4
        s.string()
        assertEquals(Span(4, 10, 2, 3), s.spanFrom(4))
    }

    @Test fun disallowedCharactersAreRefused() {
        assertTrue(error { str("\"a\u0001b\"") }.message!!.startsWith("Character U+0001 is not allowed"))
        assertEquals("a", str("a\u200Eb"))   // a direction mark ends an identifier
    }
}
