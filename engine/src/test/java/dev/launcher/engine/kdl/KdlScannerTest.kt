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
        assertEquals("\u65E5\u672C", str("\u65E5\u672C x"))
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
        assertEquals("\u00E9\uD83D\uDE00", str("\"\\u{e9}\\u{1F600}\""))
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

    @Test fun rawStrings() {
        assertEquals("\\n stays", str("#\"\\n stays\"#"))
        assertEquals("hello\\n\\r\\asd\"#world", str("##\"hello\\n\\r\\asd\"#world\"##"))
        assertEquals("", str("#\"\"#"))
        assertNull(str("#true"))
        assertEquals("Unclosed string at 1:1", error { str("#\"never\"") }.message)
    }

    @Test fun multiLineStringsDedentByTheirLastLine() {
        assertEquals("    foo\nThis is the base indentation\n        bar",
            str("\"\"\"\n        foo\n    This is the base indentation\n            bar\n    \"\"\""))
        assertEquals("Indented a bit\n\nA second paragraph.", str("\"\"\"\n    Indented a bit\n  \n    A second paragraph.\n    \"\"\""))
        assertEquals("", str("\"\"\"\n\"\"\""))
        assertEquals("a\nb", str("\"\"\"\r\n  a\r\n  b\r\n  \"\"\""))                       // literal newlines become LF
        assertEquals("\r\n\nfoo", str("\"\"\"\n    \\r\\n\n    foo\n    \"\"\""))              // escaped ones stay
        assertEquals("foo bar\nbaz", str("\"\"\"\n  foo \\\nbar\n  baz\n  \\   \"\"\""))     // whitespace escapes first
        assertEquals("Here's a \"\"\"\n    inner\n    \"\"\"\nraw.",
            str("#\"\"\"\n    Here's a \"\"\"\n        inner\n        \"\"\"\n    raw.\n    \"\"\"#"))
        assertTrue(error { str("\"\"\"one line\"\"\"") }.message!!.startsWith("A multi-line string must start with a newline"))
        assertTrue(error { str("\"\"\"\n  text\"\"\"") }.message!!.startsWith("A multi-line string's closing quotes must be alone"))
        assertTrue(error { str("\"\"\"\n\ta\n  b\n \t\n\t\"\"\"") }.message!!.startsWith("Every line of a multi-line string"))
        assertTrue(error { str("\"\"\"\n  foo\n  bar\\\n  \"\"\"") }.message!!.startsWith("A multi-line string's closing quotes"))
        assertEquals("\tx", str("\"\"\"\n  \\tx\n  \"\"\""))                                  // an escaped tab is text, not indentation
    }

    @Test fun lineContinuations() {
        val s = Scan("  \\  /* c */ // comment\n  next")
        assertTrue(s.nodeSpace())
        assertEquals('n', s.ch())
        assertTrue(Scan("\\").nodeSpace())
        assertEquals("A line continuation '\\' must end its line, found 'x' at 1:3", error { Scan("\\ x").nodeSpace() }.message)
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
        assertEquals("Character U+0001 is not allowed in a KDL document at 1:3", error { Scan("\"a\u0001b\"").checkLiterals() }.message)
        assertEquals("Character U+200F is not allowed in a KDL document at 1:6", error { Scan("// a \u200F").checkLiterals() }.message)
        assertTrue(error { Scan("x\uD800y").checkLiterals() }.message!!.startsWith("Character U+D800"))
        Scan("emoji \uD83D\uDE00 in a comment").checkLiterals()
        assertEquals("a", str("a\u200Eb"))   // a direction mark ends an identifier
    }
}
